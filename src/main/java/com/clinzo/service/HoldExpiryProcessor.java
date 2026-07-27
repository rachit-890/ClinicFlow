package com.clinzo.service;

import com.clinzo.domain.Booking;
import com.clinzo.domain.BookingStatus;
import com.clinzo.domain.Slot;
import com.clinzo.domain.SlotStatus;
import com.clinzo.repository.BookingRepository;
import com.clinzo.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryProcessor {

    private final SlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final AuditService auditService;
    private final StringRedisTemplate redisTemplate;

    /**
     * Attempts to atomically expire a hold on a slot.
     * Uses REQUIRES_NEW to ensure partial failure isolation (if one expiry fails, others in the sweep continue).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireHoldForBooking(Booking booking) {
        Long slotId = booking.getSlotId();
        
        Slot slot = slotRepository.findById(slotId).orElse(null);
        if (slot == null) {
            log.warn("Slot {} not found during hold expiry for booking {}", slotId, booking.getId());
            return;
        }

        // Atomic Optimistic Lock Update: HELD -> AVAILABLE
        int updatedRows = slotRepository.updateStatusWithOptimisticLock(
                slotId,
                SlotStatus.AVAILABLE,
                slot.getVersion(),
                List.of(SlotStatus.HELD),
                Instant.now()
        );

        // 0-Row Outcome: The atomic update affected 0 rows.
        // This means confirmBooking already won this slot in the interim.
        // This is NOT an error: expireHoldForSlot must return/complete normally, write NO AuditLog entry,
        // and the scheduler's try/catch loop must not treat this as a failure.
        if (updatedRows == 0) {
            log.debug("Hold expiry no-op: Slot {} was no longer HELD (likely confirmed concurrently)", slotId);
            return; // Graceful exit, no exception, no audit log
        }

        // Successfully reclaimed the slot. Now update the Booking status to EXPIRED.
        booking.setStatus(BookingStatus.EXPIRED);
        bookingRepository.save(booking);

        // Clean up Redis key
        redisTemplate.delete(BookingService.HOLD_KEY_PREFIX + slotId);

        // Write audit log using the consolidated AuditService
        auditService.log("SLOT", slotId, "HOLD_EXPIRED", "SYSTEM", SlotStatus.HELD.name(), SlotStatus.AVAILABLE.name());
        
        log.info("Successfully expired hold for slot {} (booking {})", slotId, booking.getId());
    }
}
