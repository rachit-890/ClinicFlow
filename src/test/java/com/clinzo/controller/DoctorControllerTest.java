package com.clinzo.controller;

import com.clinzo.AbstractIntegrationTest;
import com.clinzo.domain.*;
import com.clinzo.dto.UpdateAvailabilityWindowRequestDTO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DoctorControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AvailabilityWindowRepository windowRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;

    private Doctor doctor;
    private AvailabilityWindow window;
    private Slot slot1;
    private Slot slot2;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
        windowRepository.deleteAll();
        doctorRepository.deleteAll();

        doctor = doctorRepository.save(Doctor.builder()
                .name("Dr. Doctor Controller")
                .timezone("UTC")
                .build());

        window = windowRepository.save(AvailabilityWindow.builder()
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
                .status(SlotStatus.AVAILABLE)
                .version(0)
                .build());

        slot2 = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T16:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T16:30:00Z"))
                .status(SlotStatus.BOOKED)
                .version(1)
                .build());

        bookingRepository.save(Booking.builder()
                .slotId(slot2.getId())
                .patientId("patient-doc-ctrl")
                .status(BookingStatus.CONFIRMED)
                .build());
    }

    @Test
    @DisplayName("PUT /doctors/{doctorId}/availability/{windowId} returns 200 OK with warnings")
    void updateAvailabilityWindow_returns200WithWarnings() throws Exception {
        UpdateAvailabilityWindowRequestDTO request = UpdateAvailabilityWindowRequestDTO.builder()
                .startTimeUtc(Instant.parse("2026-08-01T09:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T12:00:00Z"))
                .build();

        mockMvc.perform(put("/doctors/" + doctor.getId() + "/availability/" + window.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorId").value(doctor.getId()))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.prunedSlotsCount").value(0))
                .andExpect(jsonPath("$.warnings[0]").exists());

        Slot preservedBookedSlot = slotRepository.findById(slot2.getId()).orElseThrow();
        assertThat(preservedBookedSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    @DisplayName("GET /doctors/{doctorId}/slots returns 200 OK and slots in requested timezone")
    void getAvailableSlots_returns200() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/doctors/" + doctor.getId() + "/slots")
                        .param("date", "2026-08-01")
                        .param("tz", "America/New_York"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].doctorId").value(doctor.getId()))
                .andExpect(jsonPath("$[0].startTime").value(org.hamcrest.Matchers.containsString("-04:00")))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("GET /doctors/{doctorId}/slots with invalid tz returns 400 Bad Request")
    void getAvailableSlots_invalidTz_returns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/doctors/" + doctor.getId() + "/slots")
                        .param("date", "2026-08-01")
                        .param("tz", "Not/AZone"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid timezone parameter: Not/AZone"));
    }
}
