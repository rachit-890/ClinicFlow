package com.clinzo.repository;

import com.clinzo.domain.Booking;
import com.clinzo.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findBySlotIdAndStatus(Long slotId, BookingStatus status);

    List<Booking> findBySlotIdIn(List<Long> slotIds);

    Optional<Booking> findBySlotIdAndHoldToken(Long slotId, String holdToken);

    long countBySlotIdAndStatus(Long slotId, BookingStatus status);

    @org.springframework.data.jpa.repository.Query("""
        SELECT b FROM Booking b
        JOIN Slot s ON b.slotId = s.id
        WHERE b.status = :bookingStatus
          AND s.status = :slotStatus
          AND b.holdExpiresAt < :now
        """)
    List<Booking> findExpiredHolds(
        @org.springframework.data.repository.query.Param("now") java.time.Instant now,
        @org.springframework.data.repository.query.Param("bookingStatus") BookingStatus bookingStatus,
        @org.springframework.data.repository.query.Param("slotStatus") com.clinzo.domain.SlotStatus slotStatus
    );
}
