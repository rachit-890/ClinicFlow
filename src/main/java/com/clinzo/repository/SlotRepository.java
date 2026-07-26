package com.clinzo.repository;

import com.clinzo.domain.Slot;
import com.clinzo.domain.SlotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    List<Slot> findByDoctorIdAndStatusAndStartTimeUtcBetween(
            Long doctorId, SlotStatus status, Instant from, Instant to);

    List<Slot> findByDoctorIdAndStartTimeUtcBetween(Long doctorId, Instant from, Instant to);

    List<Slot> findByAvailabilityWindowIdAndStatus(Long windowId, SlotStatus status);

    /**
     * Optimistic-lock booking: update only if the current version matches.
     * Returns the count of rows updated (1 = success, 0 = conflict).
     */
    @Modifying
    @Query("""
        UPDATE Slot s SET s.status = :newStatus, s.version = s.version + 1, s.updatedAt = :now
        WHERE s.id = :id AND s.version = :expectedVersion AND s.status IN :allowedStatuses
        """)
    int updateStatusWithOptimisticLock(
            @Param("id") Long id,
            @Param("newStatus") SlotStatus newStatus,
            @Param("expectedVersion") int expectedVersion,
            @Param("allowedStatuses") List<SlotStatus> allowedStatuses,
            @Param("now") Instant now);

    /**
     * Find held slots past their expiry — for the scheduled sweeper.
     */
    @Query("SELECT s FROM Slot s WHERE s.status = 'HELD' AND s.id IN " +
           "(SELECT b.slotId FROM Booking b WHERE b.holdExpiresAt < :now AND b.status = 'HELD')")
    List<Slot> findExpiredHolds(@Param("now") Instant now);

    Optional<Slot> findByIdAndDoctorId(Long id, Long doctorId);
}
