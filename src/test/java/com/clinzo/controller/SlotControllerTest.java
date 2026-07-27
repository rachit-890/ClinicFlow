package com.clinzo.controller;

import com.clinzo.AbstractIntegrationTest;
import com.clinzo.domain.*;
import com.clinzo.dto.HoldRequestDTO;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SlotControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AvailabilityWindowRepository windowRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    private Slot availableSlot;
    private Slot bookedSlot;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
        windowRepository.deleteAll();
        doctorRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        Doctor doctor = doctorRepository.save(Doctor.builder()
                .name("Dr. Slot Test")
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

        availableSlot = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T10:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T10:30:00Z"))
                .status(SlotStatus.AVAILABLE)
                .version(0)
                .build());

        bookedSlot = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T11:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T11:30:00Z"))
                .status(SlotStatus.BOOKED)
                .version(1)
                .build());
    }

    @Test
    @DisplayName("POST /slots/{id}/hold returns 200 OK on successful hold")
    void holdSlot_success_returns200() throws Exception {
        HoldRequestDTO request = HoldRequestDTO.builder().patientId("patient-hold-1").build();

        mockMvc.perform(post("/slots/" + availableSlot.getId() + "/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(availableSlot.getId()))
                .andExpect(jsonPath("$.holdToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        Slot heldSlot = slotRepository.findById(availableSlot.getId()).orElseThrow();
        assertThat(heldSlot.getStatus()).isEqualTo(SlotStatus.HELD);
    }

    @Test
    @DisplayName("POST /slots/{id}/hold returns 409 Conflict when slot is already booked")
    void holdSlot_conflict_returns409() throws Exception {
        HoldRequestDTO request = HoldRequestDTO.builder().patientId("patient-hold-2").build();

        mockMvc.perform(post("/slots/" + bookedSlot.getId() + "/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }
}
