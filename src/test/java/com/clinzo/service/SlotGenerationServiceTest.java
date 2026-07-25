package com.clinzo.service;

import com.clinzo.domain.AvailabilityWindow;
import com.clinzo.domain.Doctor;
import com.clinzo.domain.Slot;
import com.clinzo.repository.AvailabilityWindowRepository;
import com.clinzo.repository.DoctorRepository;
import com.clinzo.repository.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.clinzo.TestcontainersConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slot generation tests against a real Postgres 16 instance (Testcontainers).
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class SlotGenerationServiceTest {

    @Autowired private SlotGenerationService slotGenerationService;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AvailabilityWindowRepository windowRepository;
    @Autowired private SlotRepository slotRepository;

    private Doctor doctor;

    @BeforeEach
    void setUp() {
        slotRepository.deleteAll();
        windowRepository.deleteAll();
        doctorRepository.deleteAll();

        doctor = doctorRepository.save(Doctor.builder()
                .name("Dr. Test")
                .timezone("Asia/Kolkata")
                .build());
    }

    private AvailabilityWindow createWindow(int durationMinutes, int bufferMinutes,
                                            Instant start, Instant end) {
        return windowRepository.save(AvailabilityWindow.builder()
                .doctorId(doctor.getId())
                .dayOfWeek((short) 1)
                .isRecurring(true)
                .startTimeUtc(start)
                .endTimeUtc(end)
                .slotDurationMinutes(durationMinutes)
                .bufferMinutes(bufferMinutes)
                .appointmentType("GENERAL")
                .active(true)
                .build());
    }

    @Test
    @DisplayName("Exact division: 120 min window / 30 min slots = 4 slots, no buffer")
    void exactDivision_noBuffer() {
        Instant start = Instant.parse("2026-07-28T09:00:00Z");
        Instant end   = Instant.parse("2026-07-28T11:00:00Z"); // 120 min

        AvailabilityWindow window = createWindow(30, 0, start, end);
        List<Slot> slots = slotGenerationService.generateSlots(window);

        assertThat(slots).hasSize(4);
        assertThat(slots.get(0).getStartTimeUtc()).isEqualTo(Instant.parse("2026-07-28T09:00:00Z"));
        assertThat(slots.get(0).getEndTimeUtc()).isEqualTo(Instant.parse("2026-07-28T09:30:00Z"));
        assertThat(slots.get(3).getStartTimeUtc()).isEqualTo(Instant.parse("2026-07-28T10:30:00Z"));
        assertThat(slots.get(3).getEndTimeUtc()).isEqualTo(Instant.parse("2026-07-28T11:00:00Z"));
    }

    @Test
    @DisplayName("Remainder minutes: 130 min window / 30 min slots = 4 slots, 10 min remainder discarded")
    void remainderDiscarded() {
        Instant start = Instant.parse("2026-07-28T09:00:00Z");
        Instant end   = Instant.parse("2026-07-28T11:10:00Z"); // 130 min

        AvailabilityWindow window = createWindow(30, 0, start, end);
        List<Slot> slots = slotGenerationService.generateSlots(window);

        assertThat(slots).hasSize(4);
    }

    @Test
    @DisplayName("Buffer eating into last slot: 120 min window / 30 min slots / 10 min buffer = 3 slots")
    void bufferReducesSlotCount() {
        // With 10-min buffer: each slot occupies 40 min (30+10)
        // 3 slots = 30+10 + 30+10 + 30 = 120 min (last slot has no trailing buffer needed)
        Instant start = Instant.parse("2026-07-28T09:00:00Z");
        Instant end   = Instant.parse("2026-07-28T11:00:00Z"); // 120 min

        AvailabilityWindow window = createWindow(30, 10, start, end);
        List<Slot> slots = slotGenerationService.generateSlots(window);

        assertThat(slots).hasSize(3);
        // Slot 1: 09:00-09:30, then 10 min buffer
        // Slot 2: 09:40-10:10, then 10 min buffer
        // Slot 3: 10:20-10:50, then 10 min buffer would push to 11:00 = cursor
        // Slot 4 would start at 11:00, end at 11:30 — past window end → discarded
        assertThat(slots.get(0).getStartTimeUtc()).isEqualTo(Instant.parse("2026-07-28T09:00:00Z"));
        assertThat(slots.get(1).getStartTimeUtc()).isEqualTo(Instant.parse("2026-07-28T09:40:00Z"));
        assertThat(slots.get(2).getStartTimeUtc()).isEqualTo(Instant.parse("2026-07-28T10:20:00Z"));
    }

    @Test
    @DisplayName("Idempotent: re-running generation doesn't duplicate slots")
    void idempotentGeneration() {
        Instant start = Instant.parse("2026-07-28T09:00:00Z");
        Instant end   = Instant.parse("2026-07-28T11:00:00Z");

        AvailabilityWindow window = createWindow(30, 0, start, end);

        List<Slot> first = slotGenerationService.generateSlots(window);
        assertThat(first).hasSize(4);

        // Re-run — should not create duplicates
        List<Slot> second = slotGenerationService.generateSlots(window);
        assertThat(second).isEmpty(); // all were duplicates

        // Total in DB is still 4
        assertThat(slotRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("Inactive window: no slots generated")
    void inactiveWindow_noSlots() {
        AvailabilityWindow window = windowRepository.save(AvailabilityWindow.builder()
                .doctorId(doctor.getId())
                .dayOfWeek((short) 1)
                .isRecurring(true)
                .startTimeUtc(Instant.parse("2026-07-28T09:00:00Z"))
                .endTimeUtc(Instant.parse("2026-07-28T11:00:00Z"))
                .slotDurationMinutes(30)
                .bufferMinutes(0)
                .appointmentType("GENERAL")
                .active(false)
                .build());

        List<Slot> slots = slotGenerationService.generateSlots(window);
        assertThat(slots).isEmpty();
    }

    @Test
    @DisplayName("Window too short for even one slot")
    void windowTooShort() {
        Instant start = Instant.parse("2026-07-28T09:00:00Z");
        Instant end   = Instant.parse("2026-07-28T09:20:00Z"); // 20 min < 30 min slot

        AvailabilityWindow window = createWindow(30, 0, start, end);
        List<Slot> slots = slotGenerationService.generateSlots(window);

        assertThat(slots).isEmpty();
    }
}
