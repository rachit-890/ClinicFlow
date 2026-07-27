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

    /**
     * Prunes (deletes) slots in AVAILABLE status that fall outside the specified time range for an availability window.
     * Scoped strictly to s.status = 'AVAILABLE' to prevent deleting slots that concurrently transitioned to BOOKED/HELD.
     */
    @Modifying
    @Query("""
        DELETE FROM Slot s
        WHERE s.availabilityWindowId = :windowId
          AND s.status = com.clinzo.domain.SlotStatus.AVAILABLE
          AND (s.startTimeUtc < :newStart OR s.endTimeUtc > :newEnd)
        """)
    int deleteAvailableSlotsOutsideRange(
            @Param("windowId") Long windowId,
            @Param("newStart") Instant newStart,
            @Param("newEnd") Instant newEnd);

    /**
     * Finds slots with BOOKED or HELD status that fall outside the specified time range for an availability window.
     */
    @Query("""
        SELECT s FROM Slot s
        WHERE s.availabilityWindowId = :windowId
          AND s.status IN (com.clinzo.domain.SlotStatus.BOOKED, com.clinzo.domain.SlotStatus.HELD)
          AND (s.startTimeUtc < :newStart OR s.endTimeUtc > :newEnd)
        """)
    List<Slot> findBookedOrHeldSlotsOutsideRange(
            @Param("windowId") Long windowId,
            @Param("newStart") Instant newStart,
            @Param("newEnd") Instant newEnd);

    /**
     * Prunes (deletes) all slots in AVAILABLE status for an availability window.
     */
    @Modifying
    @Query("""
        DELETE FROM Slot s
        WHERE s.availabilityWindowId = :windowId
          AND s.status = com.clinzo.domain.SlotStatus.AVAILABLE
        """)
    int deleteAllAvailableSlotsByWindowId(@Param("windowId") Long windowId);

    /**
     * Finds all slots with BOOKED or HELD status for an availability window.
     */
    @Query("""
        SELECT s FROM Slot s
        WHERE s.availabilityWindowId = :windowId
          AND s.status IN (com.clinzo.domain.SlotStatus.BOOKED, com.clinzo.domain.SlotStatus.HELD)
        """)
    List<Slot> findAllBookedOrHeldSlotsByWindowId(@Param("windowId") Long windowId);

    /**
     * Finds available slots for a doctor within a UTC time range, optionally filtering by appointment type.
     * Joins with AvailabilityWindow to avoid N+1 queries when filtering by type.
     */
    @Query("""
        SELECT s FROM Slot s
        JOIN AvailabilityWindow aw ON s.availabilityWindowId = aw.id
        WHERE s.doctorId = :doctorId
          AND s.status = com.clinzo.domain.SlotStatus.AVAILABLE
          AND s.startTimeUtc >= :from
          AND s.startTimeUtc < :to
          AND (:appointmentType IS NULL OR aw.appointmentType = :appointmentType)
        ORDER BY s.startTimeUtc ASC
        """)
    List<Slot> findAvailableSlotsWithFilters(
            @Param("doctorId") Long doctorId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("appointmentType") String appointmentType);
}
