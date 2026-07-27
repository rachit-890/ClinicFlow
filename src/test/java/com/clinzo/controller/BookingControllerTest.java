package com.clinzo.controller;

import com.clinzo.AbstractIntegrationTest;
import com.clinzo.domain.*;
import com.clinzo.dto.RescheduleRequestDTO;
import com.clinzo.repository.AvailabilityWindowRepository;
import com.clinzo.repository.BookingRepository;
import com.clinzo.repository.DoctorRepository;
import com.clinzo.repository.SlotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AvailabilityWindowRepository windowRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;

    private Slot slot1;
    private Slot slot2;
    private Booking booking1;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
        windowRepository.deleteAll();
        doctorRepository.deleteAll();

        Doctor doctor = doctorRepository.save(Doctor.builder()
                .name("Dr. Controller Test")
                .timezone("UTC")
                .build());

        AvailabilityWindow window = windowRepository.save(AvailabilityWindow.builder()
                .doctorId(doctor.getId())
                .dayOfWeek((short) 1)
                .isRecurring(true)
                .startTimeUtc(Instant.parse("2026-08-01T09:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T17:00:00Z"))
                .slotDurationMinutes(30)
                .bufferMinutes(0)
                .appointmentType("GENERAL")
                .active(true)
                .build());

        slot1 = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T09:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T09:30:00Z"))
                .status(SlotStatus.BOOKED)
                .version(1)
                .build());

        slot2 = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T10:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T10:30:00Z"))
                .status(SlotStatus.AVAILABLE)
                .version(0)
                .build());

        booking1 = bookingRepository.save(Booking.builder()
                .slotId(slot1.getId())
                .patientId("patient-ctrl-1")
                .status(BookingStatus.CONFIRMED)
                .build());
    }

    @Test
    @DisplayName("DELETE /bookings/{id} returns 200 OK on successful cancellation")
    void cancelBooking_success_returns200() throws Exception {
        mockMvc.perform(delete("/bookings/" + booking1.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(booking1.getId()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Booking cancelled = bookingRepository.findById(booking1.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);

        Slot slot = slotRepository.findById(slot1.getId()).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    @DisplayName("DELETE /bookings/{id} returns 404 Not Found when booking ID does not exist")
    void cancelBooking_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/bookings/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Booking not found with ID: 999999"));
    }

    @Test
    @DisplayName("DELETE /bookings/{id} returns 409 Conflict when booking is already CANCELLED")
    void cancelBooking_conflict_returns409() throws Exception {
        // Cancel first time
        mockMvc.perform(delete("/bookings/" + booking1.getId())).andExpect(status().isOk());

        // Cancel second time -> 409 Conflict
        mockMvc.perform(delete("/bookings/" + booking1.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Booking " + booking1.getId() + " cannot be cancelled because its status is CANCELLED"));
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule returns 200 OK on successful reschedule")
    void rescheduleBooking_success_returns200() throws Exception {
        RescheduleRequestDTO request = RescheduleRequestDTO.builder().newSlotId(slot2.getId()).build();

        mockMvc.perform(patch("/bookings/" + booking1.getId() + "/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(slot2.getId()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        Booking oldBooking = bookingRepository.findById(booking1.getId()).orElseThrow();
        assertThat(oldBooking.getStatus()).isEqualTo(BookingStatus.RESCHEDULED);

        Slot freedSlot = slotRepository.findById(slot1.getId()).orElseThrow();
        assertThat(freedSlot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);

        Slot bookedSlot = slotRepository.findById(slot2.getId()).orElseThrow();
        assertThat(bookedSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule returns 409 Conflict when target slot is taken")
    void rescheduleBooking_conflict_returns409() throws Exception {
        Doctor doctor = doctorRepository.findAll().get(0);
        AvailabilityWindow window = windowRepository.findAll().get(0);
        Slot takenSlot = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T11:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T11:30:00Z"))
                .status(SlotStatus.BOOKED)
                .version(1)
                .build());

        RescheduleRequestDTO request = RescheduleRequestDTO.builder().newSlotId(takenSlot.getId()).build();

        mockMvc.perform(patch("/bookings/" + booking1.getId() + "/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Target slot " + takenSlot.getId() + " is already booked."));
    }
}
