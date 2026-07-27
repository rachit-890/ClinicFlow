package com.clinzo.service;

import com.clinzo.domain.Booking;
import com.clinzo.domain.BookingStatus;
import com.clinzo.domain.Slot;
import com.clinzo.domain.SlotStatus;
import com.clinzo.dto.HoldResponseDTO;
import com.clinzo.exception.ResourceNotFoundException;
import com.clinzo.exception.SlotConflictException;
import com.clinzo.repository.BookingRepository;
import com.clinzo.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldService {

    public static final String HOLD_KEY_PREFIX = "hold:";
    public static final long HOLD_TTL_SECONDS = 120;

    private final SlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Places a temporary 120-second hold on a slot using a Redis key with SET NX EX.
     * Generates an opaque UUID hold token returned to the caller.
     *
     * @param slotId    target slot ID
     * @param patientId patient placing the hold
     * @return HoldResponseDTO containing opaque token and expiration
     */
    @Transactional
    public HoldResponseDTO holdSlot(Long slotId, String patientId) {
        Slot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found with ID: " + slotId));

        if (slot.getStatus() != SlotStatus.AVAILABLE) {
            throw new SlotConflictException("Slot " + slotId + " is not available for hold (current status: " + slot.getStatus() + ")");
        }

        String holdToken = UUID.randomUUID().toString();
        String redisKey = HOLD_KEY_PREFIX + slotId;

        // Atomic Redis SET key token NX EX 120
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, holdToken, Duration.ofSeconds(HOLD_TTL_SECONDS));

        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("Redis hold acquisition failed for slot {} — already held", slotId);
            throw new SlotConflictException("Slot " + slotId + " is currently held by another user");
        }

        // Optimistic update of slot status AVAILABLE -> HELD
        int updated;
        try {
            updated = slotRepository.updateStatusWithOptimisticLock(
                    slotId,
                    SlotStatus.HELD,
                    slot.getVersion(),
                    List.of(SlotStatus.AVAILABLE),
                    Instant.now()
            );
        } catch (Exception e) {
            redisTemplate.delete(redisKey);
            log.warn("Exception during DB status update for slot {} — rolled back Redis key", slotId);
            throw e;
        }

        if (updated == 0) {
            // Roll back Redis hold key on DB optimistic lock failure (0 rows updated)
            redisTemplate.delete(redisKey);
            log.warn("Optimistic lock failure holding slot {} — status changed concurrently", slotId);
            throw new SlotConflictException("Concurrent modification on slot " + slotId);
        }

        Instant expiresAt = Instant.now().plusSeconds(HOLD_TTL_SECONDS);

        // Record HELD booking entry
        Booking booking = Booking.builder()
                .slotId(slotId)
                .patientId(patientId)
                .status(BookingStatus.HELD)
                .holdToken(holdToken)
                .holdExpiresAt(expiresAt)
                .build();
        bookingRepository.save(booking);

        log.info("Successfully held slot {} for patient {}, token: {}, expiresAt: {}",
                slotId, patientId, holdToken, expiresAt);

        return HoldResponseDTO.builder()
                .slotId(slotId)
                .patientId(patientId)
                .holdToken(holdToken)
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * Verify if the provided hold token matches the active Redis key.
     */
    public boolean validateHoldToken(Long slotId, String holdToken) {
        if (holdToken == null || holdToken.isBlank()) {
            return false;
        }
        String redisKey = HOLD_KEY_PREFIX + slotId;
        String storedToken = redisTemplate.opsForValue().get(redisKey);
        return holdToken.equals(storedToken);
    }

    /**
     * Release a hold explicitly (e.g. user canceled hold or expiration).
     */
    @Transactional
    public void releaseHold(Long slotId, String holdToken) {
        String redisKey = HOLD_KEY_PREFIX + slotId;
        String storedToken = redisTemplate.opsForValue().get(redisKey);

        if (storedToken != null && storedToken.equals(holdToken)) {
            redisTemplate.delete(redisKey);

            slotRepository.findById(slotId).ifPresent(slot -> {
                if (slot.getStatus() == SlotStatus.HELD) {
                    slotRepository.updateStatusWithOptimisticLock(
                            slotId, SlotStatus.AVAILABLE, slot.getVersion(),
                            List.of(SlotStatus.HELD), Instant.now());
                }
            });

            bookingRepository.findBySlotIdAndHoldToken(slotId, holdToken)
                    .ifPresent(booking -> {
                        booking.setStatus(BookingStatus.CANCELLED);
                        bookingRepository.save(booking);
                    });
        }
    }
}
