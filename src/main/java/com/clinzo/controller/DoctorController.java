package com.clinzo.controller;

import com.clinzo.dto.SlotResponseDTO;
import com.clinzo.dto.UpdateAvailabilityWindowRequestDTO;
import com.clinzo.dto.UpdateAvailabilityWindowResponseDTO;
import com.clinzo.service.AvailabilityService;
import com.clinzo.service.SlotQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/doctors")
@RequiredArgsConstructor
@Slf4j
public class DoctorController {

    private final AvailabilityService availabilityService;
    private final SlotQueryService slotQueryService;

    @GetMapping("/{doctorId}/slots")
    public ResponseEntity<List<SlotResponseDTO>> getAvailableSlots(
            @PathVariable Long doctorId,
            @RequestParam LocalDate date,
            @RequestParam(required = false) String tz,
            @RequestParam(required = false) String type) {

        log.info("REST request to get available slots for doctor {} on date {}", doctorId, date);
        List<SlotResponseDTO> slots = slotQueryService.getAvailableSlots(doctorId, date, tz, type);
        return ResponseEntity.ok(slots);
    }

    @PutMapping("/{doctorId}/availability/{windowId}")
    public ResponseEntity<UpdateAvailabilityWindowResponseDTO> updateAvailabilityWindow(
            @PathVariable Long doctorId,
            @PathVariable Long windowId,
            @Valid @RequestBody UpdateAvailabilityWindowRequestDTO request) {

        log.info("REST request to update availability window {} for doctor {}", windowId, doctorId);
        UpdateAvailabilityWindowResponseDTO response = availabilityService.updateAvailabilityWindow(doctorId, windowId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{doctorId}/availability/{windowId}")
    public ResponseEntity<UpdateAvailabilityWindowResponseDTO> removeAvailabilityWindow(
            @PathVariable Long doctorId,
            @PathVariable Long windowId) {

        log.info("REST request to remove availability window {} for doctor {}", windowId, doctorId);
        UpdateAvailabilityWindowResponseDTO response = availabilityService.removeAvailabilityWindow(doctorId, windowId);
        return ResponseEntity.ok(response);
    }
}
