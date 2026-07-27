package com.clinzo.service;

import com.clinzo.domain.AvailabilityWindow;
import com.clinzo.domain.Slot;
import com.clinzo.dto.UpdateAvailabilityWindowRequestDTO;
import com.clinzo.dto.UpdateAvailabilityWindowResponseDTO;
import com.clinzo.exception.ResourceNotFoundException;
import com.clinzo.repository.AvailabilityWindowRepository;
import com.clinzo.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AvailabilityService {

    private final AvailabilityWindowRepository windowRepository;
    private final SlotRepository slotRepository;
    private final SlotGenerationService slotGenerationService;
    private final AuditService auditService;

    /**
     * Updates an existing doctor availability window in a single transaction.
     * Prunes stale AVAILABLE slots outside the new time range.
     * Preserves BOOKED and HELD slots untouched and collects warnings for any outside the new range.
     * Deactivates the old window and creates a new active window for future slot generation.
     *
     * @param doctorId Doctor ID
     * @param windowId Window ID to update
     * @param request Update request payload
     * @return UpdateAvailabilityWindowResponseDTO containing new window info, pruned slot count, and warnings
     */
    @Transactional
    public UpdateAvailabilityWindowResponseDTO updateAvailabilityWindow(
            Long doctorId, Long windowId, UpdateAvailabilityWindowRequestDTO request) {

        AvailabilityWindow existingWindow = windowRepository.findById(windowId)
                .orElseThrow(() -> new ResourceNotFoundException("Availability window not found with ID: " + windowId));

        if (!existingWindow.getDoctorId().equals(doctorId)) {
            throw new IllegalArgumentException("Availability window " + windowId + " does not belong to doctor " + doctorId);
        }

        Instant newStart = request.getStartTimeUtc();
        Instant newEnd = request.getEndTimeUtc();

        if (!newEnd.isAfter(newStart)) {
            throw new IllegalArgumentException("endTimeUtc must be after startTimeUtc");
        }

        // Step 1: Prune AVAILABLE slots outside new range.
        // Scoped strictly to status = 'AVAILABLE' so concurrent bookings (status = 'BOOKED') are untouched.
        int prunedCount = slotRepository.deleteAvailableSlotsOutsideRange(windowId, newStart, newEnd);
        log.info("Pruned {} stale AVAILABLE slots for window {}", prunedCount, windowId);

        // Step 2: Find BOOKED or HELD slots that fall outside new range (must be preserved untouched)
        List<Slot> outOfBoundsSlots = slotRepository.findBookedOrHeldSlotsOutsideRange(windowId, newStart, newEnd);
        List<String> warnings = new ArrayList<>();
        for (Slot slot : outOfBoundsSlots) {
            String warningMsg = String.format(
                    "Slot %d (%s - %s) has status %s but falls outside updated window hours (%s - %s). Cancel or reschedule manually if needed.",
                    slot.getId(), slot.getStartTimeUtc(), slot.getEndTimeUtc(), slot.getStatus(), newStart, newEnd
            );
            warnings.add(warningMsg);
            log.warn("Preserved out-of-bounds slot during availability update: {}", warningMsg);
        }

        // Step 3: Deactivate old window
        existingWindow.setActive(false);
        windowRepository.save(existingWindow);

        // Step 4: Create new active window
        AvailabilityWindow newWindow = windowRepository.save(AvailabilityWindow.builder()
                .doctorId(doctorId)
                .dayOfWeek(request.getDayOfWeek() != null ? request.getDayOfWeek() : existingWindow.getDayOfWeek())
                .isRecurring(request.getIsRecurring() != null ? request.getIsRecurring() : existingWindow.getIsRecurring())
                .startTimeUtc(newStart)
                .endTimeUtc(newEnd)
                .slotDurationMinutes(request.getSlotDurationMinutes() != null ? request.getSlotDurationMinutes() : existingWindow.getSlotDurationMinutes())
                .bufferMinutes(request.getBufferMinutes() != null ? request.getBufferMinutes() : existingWindow.getBufferMinutes())
                .appointmentType(request.getAppointmentType() != null ? request.getAppointmentType() : existingWindow.getAppointmentType())
                .active(true)
                .build());

        log.info("Created updated active window {} for doctor {}", newWindow.getId(), doctorId);

        // Step 5: Trigger slot generation for the new window
        slotGenerationService.generateSlots(newWindow);

        // Step 6: Audit log
        auditService.log("AVAILABILITY_WINDOW", newWindow.getId(), "UPDATE", doctorId.toString(), "WINDOW_" + windowId, "ACTIVE");

        return UpdateAvailabilityWindowResponseDTO.builder()
                .windowId(newWindow.getId())
                .doctorId(doctorId)
                .startTimeUtc(newWindow.getStartTimeUtc())
                .endTimeUtc(newWindow.getEndTimeUtc())
                .active(newWindow.getActive())
                .prunedSlotsCount(prunedCount)
                .warnings(warnings)
                .build();
    }

    /**
     * Removes an existing doctor availability window in a single transaction.
     * Prunes all AVAILABLE slots for this window.
     * Preserves BOOKED and HELD slots untouched and collects warnings.
     * Deactivates the window and does not create a replacement.
     */
    @Transactional
    public UpdateAvailabilityWindowResponseDTO removeAvailabilityWindow(Long doctorId, Long windowId) {
        AvailabilityWindow existingWindow = windowRepository.findById(windowId)
                .orElseThrow(() -> new ResourceNotFoundException("Availability window not found with ID: " + windowId));

        if (!existingWindow.getDoctorId().equals(doctorId)) {
            throw new IllegalArgumentException("Availability window " + windowId + " does not belong to doctor " + doctorId);
        }

        // Step 1: Prune all AVAILABLE slots for this window
        int prunedCount = slotRepository.deleteAllAvailableSlotsByWindowId(windowId);
        log.info("Pruned {} stale AVAILABLE slots for removed window {}", prunedCount, windowId);

        // Step 2: Find all BOOKED or HELD slots (must be preserved untouched)
        List<Slot> preservedSlots = slotRepository.findAllBookedOrHeldSlotsByWindowId(windowId);
        List<String> warnings = new ArrayList<>();
        for (Slot slot : preservedSlots) {
            String warningMsg = String.format(
                    "Slot %d (%s - %s) has status %s but its parent window was removed. Cancel or reschedule manually if needed.",
                    slot.getId(), slot.getStartTimeUtc(), slot.getEndTimeUtc(), slot.getStatus()
            );
            warnings.add(warningMsg);
            log.warn("Preserved slot during window removal: {}", warningMsg);
        }

        // Step 3: Deactivate old window
        existingWindow.setActive(false);
        windowRepository.save(existingWindow);

        // Step 4: Audit log
        auditService.log("AVAILABILITY_WINDOW", windowId, "REMOVE", doctorId.toString(), "ACTIVE", "INACTIVE");

        log.info("Removed and deactivated window {} for doctor {}", windowId, doctorId);

        return UpdateAvailabilityWindowResponseDTO.builder()
                .windowId(existingWindow.getId())
                .doctorId(doctorId)
                .startTimeUtc(existingWindow.getStartTimeUtc())
                .endTimeUtc(existingWindow.getEndTimeUtc())
                .active(existingWindow.getActive())
                .prunedSlotsCount(prunedCount)
                .warnings(warnings)
                .build();
    }
}
