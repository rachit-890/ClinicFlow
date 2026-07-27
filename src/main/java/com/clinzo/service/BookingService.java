package com.clinzo.service;

import com.clinzo.domain.AuditLog;
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
    private final AuditService auditService;
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

        // Step 1: Version Source of Truth
        Slot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found with ID: " + slotId));

        String redisKey = HOLD_KEY_PREFIX + slotId;
        boolean hasToken = holdToken != null && !holdToken.isBlank();

        // Step 2: Path A Redis Token Validation
        if (hasToken) {
            String storedToken = redisTemplate.opsForValue().get(redisKey);
            if (storedToken == null || !storedToken.equals(holdToken)) {
                log.warn("Booking confirmation rejected for slot {}: holdToken mismatch or expired", slotId);
                throw new SlotConflictException("Invalid or expired hold token for slot: " + slotId);
            }
        }

        // Step 3: Single Atomic Control-Flow UPDATE Query
        List<SlotStatus> allowedStatuses = hasToken
                ? List.of(SlotStatus.HELD, SlotStatus.AVAILABLE)
                : List.of(SlotStatus.AVAILABLE);

        int updatedRows = slotRepository.updateStatusWithOptimisticLock(
                slotId,
                SlotStatus.BOOKED,
                slot.getVersion(),
                allowedStatuses,
                Instant.now()
        );

        // Step 4: Decision evaluation & post-failure diagnostic read
        if (updatedRows == 0) {
            Slot currentSlot = slotRepository.findById(slotId).orElse(slot);
            if (!hasToken && currentSlot.getStatus() == SlotStatus.HELD) {
                log.warn("Booking confirmation rejected for slot {}: slot is HELD but no holdToken provided", slotId);
                throw new SlotConflictException("Slot " + slotId + " is currently on hold by another patient. Hold token is required.");
            } else if (currentSlot.getStatus() == SlotStatus.BOOKED) {
                log.warn("Booking confirmation rejected for slot {}: slot is already BOOKED", slotId);
                throw new SlotConflictException("Slot " + slotId + " is already booked.");
            }
            log.warn("Optimistic lock failure confirming booking for slot {} (version {})", slotId, slot.getVersion());
            throw new SlotConflictException("Slot " + slotId + " was modified concurrently or is no longer available.");
        }

        // Step 5: Persist or update Booking record
        Booking booking;
        if (hasToken) {
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

        // Step 6: Clean up Redis key
        redisTemplate.delete(redisKey);

        auditService.log("BOOKING", savedBooking.getId(), "CONFIRM", patientId, "NONE", BookingStatus.CONFIRMED.name());

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

    /**
     * Cancels an existing HELD or CONFIRMED booking in a single transaction.
     * Gates decision strictly on updated slot row count (updatedRows == 1).
     * Restores associated slot status to AVAILABLE with a version increment.
     * Deletes any Redis hold key.
     * Writes an AuditLog entry.
     *
     * @param bookingId ID of the booking to cancel
     * @return BookingResponseDTO representing the cancelled booking
     */
    @Transactional
    public BookingResponseDTO cancelBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        if (booking.getStatus() != BookingStatus.CONFIRMED && booking.getStatus() != BookingStatus.HELD) {
            throw new SlotConflictException("Booking " + bookingId + " cannot be cancelled because its status is " + booking.getStatus());
        }

        Slot slot = slotRepository.findById(booking.getSlotId())
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found with ID: " + booking.getSlotId()));

        // Single atomic control-flow UPDATE query for slot status transition to AVAILABLE
        int updatedRows = slotRepository.updateStatusWithOptimisticLock(
                slot.getId(),
                SlotStatus.AVAILABLE,
                slot.getVersion(),
                List.of(SlotStatus.BOOKED, SlotStatus.HELD),
                Instant.now()
        );

        // Gate: Decision comes SOLELY from slot updatedRows count
        if (updatedRows == 0) {
            Slot currentSlot = slotRepository.findById(slot.getId()).orElse(slot);
            Booking currentBooking = bookingRepository.findById(bookingId).orElse(booking);
            if (currentBooking.getStatus() == BookingStatus.CANCELLED) {
                throw new SlotConflictException("Booking " + bookingId + " is already cancelled.");
            } else if (currentSlot.getStatus() == SlotStatus.AVAILABLE) {
                throw new SlotConflictException("Slot " + slot.getId() + " is already available.");
            }
            throw new SlotConflictException("Optimistic lock failure cancelling slot " + slot.getId() + ". State was modified concurrently.");
        }

        // Only set booking.status = CANCELLED and write AuditLog when slot update affected exactly 1 row
        BookingStatus oldStatus = booking.getStatus();
        booking.setStatus(BookingStatus.CANCELLED);
        Booking savedBooking = bookingRepository.save(booking);

        // Delete Redis key if held
        redisTemplate.delete(HOLD_KEY_PREFIX + slot.getId());

        // Save Audit Log
        auditService.log("BOOKING", booking.getId(), "CANCEL", booking.getPatientId(), oldStatus.name(), BookingStatus.CANCELLED.name());

        log.info("Booking {} cancelled successfully for patient {}", bookingId, booking.getPatientId());

        return BookingResponseDTO.builder()
                .bookingId(savedBooking.getId())
                .slotId(savedBooking.getSlotId())
                .patientId(savedBooking.getPatientId())
                .status(savedBooking.getStatus())
                .createdAt(savedBooking.getCreatedAt())
                .build();
    }

    /**
     * Reschedules an existing CONFIRMED booking to a new slot in a SINGLE transaction.
     * Precondition check: Valid status to reschedule from is strictly CONFIRMED.
     * Book-New Step Choice: Uses Path B (Direct Booking Path) atomic optimistic update WHERE status = 'AVAILABLE'.
     * Cancel-Old Step: Atomic update WHERE status = 'BOOKED', gated on updatedRows == 1.
     * If either step fails, transaction rolls back cleanly so old booking remains CONFIRMED and old slot remains BOOKED.
     *
     * @param bookingId Existing confirmed booking ID
     * @param newSlotId Target slot ID to reschedule into
     * @return BookingResponseDTO for the new confirmed booking
     */
    @Transactional
    public BookingResponseDTO rescheduleBooking(Long bookingId, Long newSlotId) {
        Booking oldBooking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with ID: " + bookingId));

        // Precondition: Strictly CONFIRMED bookings can be rescheduled
        if (oldBooking.getStatus() != BookingStatus.CONFIRMED) {
            throw new SlotConflictException("Booking " + bookingId + " cannot be rescheduled because its status is " + oldBooking.getStatus() + ". Only CONFIRMED bookings can be rescheduled.");
        }

        Long oldSlotId = oldBooking.getSlotId();
        if (oldSlotId.equals(newSlotId)) {
            throw new IllegalArgumentException("Cannot reschedule booking to the same slot.");
        }

        Slot oldSlot = slotRepository.findById(oldSlotId)
                .orElseThrow(() -> new ResourceNotFoundException("Original slot not found with ID: " + oldSlotId));

        Slot newSlot = slotRepository.findById(newSlotId)
                .orElseThrow(() -> new ResourceNotFoundException("Target slot not found with ID: " + newSlotId));

        // Step 1: Book-New Step via Path B Atomic Optimistic Update
        int updatedNewRows = slotRepository.updateStatusWithOptimisticLock(
                newSlotId,
                SlotStatus.BOOKED,
                newSlot.getVersion(),
                List.of(SlotStatus.AVAILABLE),
                Instant.now()
        );

        if (updatedNewRows == 0) {
            Slot currentNewSlot = slotRepository.findById(newSlotId).orElse(newSlot);
            if (currentNewSlot.getStatus() == SlotStatus.HELD) {
                throw new SlotConflictException("Target slot " + newSlotId + " is currently on hold by another patient.");
            } else if (currentNewSlot.getStatus() == SlotStatus.BOOKED) {
                throw new SlotConflictException("Target slot " + newSlotId + " is already booked.");
            }
            throw new SlotConflictException("Target slot " + newSlotId + " was modified concurrently or is no longer available.");
        }

        // Step 2: Cancel-Old Step — Free Old Slot with row count check
        int updatedOldRows = slotRepository.updateStatusWithOptimisticLock(
                oldSlotId,
                SlotStatus.AVAILABLE,
                oldSlot.getVersion(),
                List.of(SlotStatus.BOOKED),
                Instant.now()
        );

        if (updatedOldRows == 0) {
            // Premise was stale (e.g. concurrent cancellation raced this reschedule)
            // Throwing exception triggers full transaction rollback of both old & new operations
            log.warn("Failed to release original slot {} during rescheduling (version conflict or state changed)", oldSlotId);
            throw new SlotConflictException("Failed to release original slot " + oldSlotId + " during rescheduling. Slot status was modified concurrently.");
        }

        // Step 3: Update Old Booking Status to RESCHEDULED
        oldBooking.setStatus(BookingStatus.RESCHEDULED);
        bookingRepository.save(oldBooking);

        // Step 4: Create New Booking Record for New Slot
        Booking newBooking = bookingRepository.save(Booking.builder()
                .slotId(newSlotId)
                .patientId(oldBooking.getPatientId())
                .status(BookingStatus.CONFIRMED)
                .build());

        // Step 5: Audit Log Entries for both Old (RESCHEDULED) and New (CONFIRMED) Bookings
        auditService.log("BOOKING", oldBooking.getId(), "RESCHEDULE_OUT", oldBooking.getPatientId(), BookingStatus.CONFIRMED.name(), BookingStatus.RESCHEDULED.name());
        auditService.log("BOOKING", newBooking.getId(), "RESCHEDULE_IN", oldBooking.getPatientId(), "NONE", BookingStatus.CONFIRMED.name());

        log.info("Booking {} successfully rescheduled to new booking {} on slot {}",
                bookingId, newBooking.getId(), newSlotId);

        return BookingResponseDTO.builder()
                .bookingId(newBooking.getId())
                .slotId(newSlotId)
                .patientId(newBooking.getPatientId())
                .status(newBooking.getStatus())
                .createdAt(newBooking.getCreatedAt())
                .build();
    }
}
