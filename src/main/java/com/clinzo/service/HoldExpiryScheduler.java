package com.clinzo.service;

import com.clinzo.domain.Booking;
import com.clinzo.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryScheduler {

    private final BookingRepository bookingRepository;
    private final HoldExpiryProcessor holdExpiryProcessor;

    @Scheduled(fixedRate = 30000)
    public void sweepExpiredHolds() {
        log.debug("Starting hold expiry sweep...");
        
        // Find bookings that are HELD, associated slot is HELD, and holdExpiresAt is in the past
        List<Booking> expiredHolds = bookingRepository.findExpiredHolds(
            Instant.now(),
            com.clinzo.domain.BookingStatus.HELD,
            com.clinzo.domain.SlotStatus.HELD
        );
        
        if (expiredHolds.isEmpty()) {
            return;
        }
        
        log.info("Found {} expired holds to process", expiredHolds.size());
        
        for (Booking booking : expiredHolds) {
            try {
                holdExpiryProcessor.expireHoldForBooking(booking);
            } catch (Exception e) {
                // Catching exception prevents one failed expiry from stopping the rest of the sweep.
                log.error("Failed to process hold expiry for booking {}: {}", booking.getId(), e.getMessage(), e);
            }
        }
        
        log.debug("Finished hold expiry sweep");
    }
}
