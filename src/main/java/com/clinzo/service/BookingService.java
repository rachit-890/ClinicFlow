package com.clinzo.service;

import com.clinzo.domain.Booking;
import com.clinzo.domain.BookingStatus;
import com.clinzo.domain.Slot;
import com.clinzo.domain.SlotStatus;
import com.clinzo.dto.BookingRequestDTO;
import com.clinzo.dto.BookingResponseDTO;
import com.clinzo.exception.ResourceNotFoundException;
import com.clinzo.exception.SlotConflictException;
import com.clinzo.repository.BookingRepository;
import com.clinzo.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    public static final String HOLD_KEY_PREFIX = "hold:";

    private final SlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Confirms a booking for a slot.
     *
     * Strict Token & Lock Check Sequence:
     * 1. If holdToken provided:
     *    a) GET hold:{slotId} from Redis
     *    b) If missing or token != stored, reject with 409 Conflict.
     * 2. Execute version-locked DB update:
     *    UPDATE slots SET status='BOOKED', version=version+1
     *    WHERE id=? AND version=? AND status IN ('AVAILABLE', 'HELD')
     * 3. If updated == 0 -> throw 409 Conflict.
     * 4. Update/Create Booking record to BOOKED.
     * 5. Remove Redis key hold:{slotId}.
     *
     * @param request BookingRequestDTO
     * @return BookingResponseDTO
     */
    @Transactional
    public BookingResponseDTO confirmBooking(BookingRequestDTO request) {
        Long slotId = request.getSlotId();
        String patientId = request.getPatientId();
        String holdToken = request.getHoldToken();

        Slot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found with ID: " + slotId));

        String redisKey = HOLD_KEY_PREFIX + slotId;

        // Step 1: Redis hold validation if holdToken provided or slot is HELD
        if (holdToken != null && !holdToken.isBlank()) {
            String storedToken = redisTemplate.opsForValue().get(redisKey);
            if (storedToken == null || !storedToken.equals(holdToken)) {
                log.warn("Booking confirmation rejected for slot {}: holdToken mismatch or expired", slotId);
                throw new SlotConflictException("Invalid or expired hold token for slot: " + slotId);
            }
        } else if (slot.getStatus() == SlotStatus.HELD) {
            // Slot is held in DB but caller didn't provide hold token
            log.warn("Booking confirmation rejected for slot {}: slot is HELD but no holdToken provided", slotId);
            throw new SlotConflictException("Slot " + slotId + " is currently on hold. Hold token is required.");
        }

        // Step 2: Optimistic Version-Locked Update
        List<SlotStatus> allowedStatuses = (holdToken != null && !holdToken.isBlank())
                ? List.of(SlotStatus.HELD, SlotStatus.AVAILABLE)
                : List.of(SlotStatus.AVAILABLE);

        int updatedRows = slotRepository.updateStatusWithOptimisticLock(
                slotId,
                SlotStatus.BOOKED,
                slot.getVersion(),
                allowedStatuses,
                Instant.now()
        );

        // Step 3: Concurrency check — exactly-one-winner semantics
        if (updatedRows == 0) {
            log.warn("Optimistic lock failure confirming booking for slot {} (version {})", slotId, slot.getVersion());
            throw new SlotConflictException("Slot " + slotId + " was modified concurrently or is no longer available.");
        }

        // Step 4: Persist or update Booking record
        Booking booking;
        if (holdToken != null && !holdToken.isBlank()) {
            Optional<Booking> existingBooking = bookingRepository.findBySlotIdAndHoldToken(slotId, holdToken);
            if (existingBooking.isPresent()) {
                booking = existingBooking.get();
                booking.setStatus(BookingStatus.CONFIRMED);
                booking.setPatientId(patientId);
            } else {
                booking = Booking.builder()
                        .slotId(slotId)
                        .patientId(patientId)
                        .status(BookingStatus.CONFIRMED)
                        .holdToken(holdToken)
                        .build();
            }
        } else {
            booking = Booking.builder()
                    .slotId(slotId)
                    .patientId(patientId)
                    .status(BookingStatus.CONFIRMED)
                    .build();
        }

        Booking savedBooking = bookingRepository.save(booking);

        // Step 5: Clean up Redis key
        redisTemplate.delete(redisKey);

        log.info("Booking confirmed successfully: bookingId={}, slotId={}, patientId={}",
                savedBooking.getId(), slotId, patientId);

        return BookingResponseDTO.builder()
                .bookingId(savedBooking.getId())
                .slotId(slotId)
                .patientId(patientId)
                .status(savedBooking.getStatus())
                .createdAt(savedBooking.getCreatedAt())
                .build();
    }
}
