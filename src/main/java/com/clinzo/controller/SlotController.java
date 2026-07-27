package com.clinzo.controller;

import com.clinzo.dto.HoldRequestDTO;
import com.clinzo.dto.HoldResponseDTO;
import com.clinzo.service.HoldService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/slots")
@RequiredArgsConstructor
@Slf4j
public class SlotController {

    private final HoldService holdService;

    /**
     * Places a hold on a specific slot for a patient.
     * POST /slots/{id}/hold
     */
    @PostMapping("/{id}/hold")
    public ResponseEntity<HoldResponseDTO> holdSlot(
            @PathVariable("id") Long id,
            @Valid @RequestBody HoldRequestDTO request) {
        log.info("REST request to hold slot {} for patient {}", id, request.getPatientId());
        HoldResponseDTO response = holdService.holdSlot(id, request.getPatientId());
        return ResponseEntity.ok(response);
    }
}
