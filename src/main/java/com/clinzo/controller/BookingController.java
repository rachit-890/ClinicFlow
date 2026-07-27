package com.clinzo.controller;

import com.clinzo.dto.BookingResponseDTO;
import com.clinzo.dto.RescheduleRequestDTO;
import com.clinzo.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

    private final BookingService bookingService;

    /**
     * Confirms a booking (either directly or via a hold token).
     * POST /bookings
     */
    @PostMapping
    public ResponseEntity<BookingResponseDTO> confirmBooking(@Valid @RequestBody com.clinzo.dto.BookingRequestDTO request) {
        log.info("REST request to confirm booking for slot {} by patient {}", request.getSlotId(), request.getPatientId());
        BookingResponseDTO response = bookingService.confirmBooking(request);
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(response);
    }

    /**
     * Cancels an existing booking.
     * DELETE /bookings/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<BookingResponseDTO> cancelBooking(@PathVariable("id") Long id) {
        log.info("REST request to cancel booking: {}", id);
        BookingResponseDTO response = bookingService.cancelBooking(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Reschedules an existing booking to a new slot.
     * PATCH /bookings/{id}/reschedule
     */
    @PatchMapping("/{id}/reschedule")
    public ResponseEntity<BookingResponseDTO> rescheduleBooking(
            @PathVariable("id") Long id,
            @Valid @RequestBody RescheduleRequestDTO request) {
        log.info("REST request to reschedule booking {} to new slot {}", id, request.getNewSlotId());
        BookingResponseDTO response = bookingService.rescheduleBooking(id, request.getNewSlotId());
        return ResponseEntity.ok(response);
    }
}
