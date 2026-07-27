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
