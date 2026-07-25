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
}
