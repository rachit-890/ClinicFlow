package com.clinzo.service;

import com.clinzo.AbstractIntegrationTest;
import com.clinzo.domain.AvailabilityWindow;
import com.clinzo.domain.Doctor;
import com.clinzo.domain.Slot;
import com.clinzo.domain.SlotStatus;
import com.clinzo.dto.SlotResponseDTO;
import com.clinzo.repository.AvailabilityWindowRepository;
import com.clinzo.repository.DoctorRepository;
import com.clinzo.repository.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SlotQueryServiceTest extends AbstractIntegrationTest {

    @Autowired private SlotQueryService slotQueryService;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AvailabilityWindowRepository windowRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private com.clinzo.repository.BookingRepository bookingRepository;

    private Doctor doctor;
    private AvailabilityWindow windowConsultation;
    private AvailabilityWindow windowFollowUp;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
        windowRepository.deleteAll();
        doctorRepository.deleteAll();

        doctor = doctorRepository.save(Doctor.builder()
                .name("Dr. Timezone")
                .timezone("Asia/Kolkata") // UTC+5:30
                .build());

        // A window that generates slots for 2026-07-28 in Asia/Kolkata
        windowConsultation = windowRepository.save(AvailabilityWindow.builder()
                .doctorId(doctor.getId())
                .dayOfWeek((short) 2)
                .isRecurring(true)
                .startTimeUtc(Instant.parse("2026-07-28T04:30:00Z")) // 10:00 AM IST
                .endTimeUtc(Instant.parse("2026-07-28T06:30:00Z"))   // 12:00 PM IST
                .slotDurationMinutes(30)
                .bufferMinutes(0)
                .appointmentType("CONSULTATION")
                .active(true)
                .build());

        // 10:00 AM IST -> 10:30 AM IST
        slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(windowConsultation.getId())
                .startTimeUtc(Instant.parse("2026-07-28T04:30:00Z"))
                .endTimeUtc(Instant.parse("2026-07-28T05:00:00Z"))
                .status(SlotStatus.AVAILABLE)
                .version(0)
                .build());

        windowFollowUp = windowRepository.save(AvailabilityWindow.builder()
                .doctorId(doctor.getId())
                .dayOfWeek((short) 2)
                .isRecurring(true)
                .startTimeUtc(Instant.parse("2026-07-28T10:30:00Z")) // 16:00 (4 PM) IST
                .endTimeUtc(Instant.parse("2026-07-28T12:30:00Z"))   // 18:00 (6 PM) IST
                .slotDurationMinutes(30)
                .bufferMinutes(0)
                .appointmentType("FOLLOW_UP")
                .active(true)
                .build());

        // 4:00 PM IST -> 4:30 PM IST
        slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(windowFollowUp.getId())
                .startTimeUtc(Instant.parse("2026-07-28T10:30:00Z"))
                .endTimeUtc(Instant.parse("2026-07-28T11:00:00Z"))
                .status(SlotStatus.AVAILABLE)
                .version(0)
                .build());
    }

    @Test
    @DisplayName("findAvailableSlots: valid timezone converts UTC boundaries and formats output correctly")
    void findSlots_validTimezone_convertsBoundariesAndResults() {
        // Querying for 2026-07-28 in Asia/Kolkata
        List<SlotResponseDTO> slots = slotQueryService.getAvailableSlots(
                doctor.getId(), LocalDate.parse("2026-07-28"), "Asia/Kolkata", null);

        assertThat(slots).hasSize(2);
        
        // Assert they are returned in IST timezone offset (+05:30)
        assertThat(slots.get(0).getStartTime().toString()).contains("+05:30[Asia/Kolkata]");
        assertThat(slots.get(1).getStartTime().toString()).contains("+05:30[Asia/Kolkata]");
        
        // Assert local time translates to 10:00 AM and 16:00 PM
        assertThat(slots.get(0).getStartTime().getHour()).isEqualTo(10);
        assertThat(slots.get(1).getStartTime().getHour()).isEqualTo(16);
    }

    @Test
    @DisplayName("findAvailableSlots: omit timezone parameter defaults to doctor's configured timezone")
    void findSlots_noTimezone_defaultsToDoctorTimezone() {
        // Querying for 2026-07-28 with NULL timezone
        List<SlotResponseDTO> slots = slotQueryService.getAvailableSlots(
                doctor.getId(), LocalDate.parse("2026-07-28"), null, null);

        assertThat(slots).hasSize(2);
        // Doctor is Asia/Kolkata, should use that
        assertThat(slots.get(0).getStartTime().toString()).contains("Asia/Kolkata");
    }

    @Test
    @DisplayName("findAvailableSlots: invalid timezone parameter throws IllegalArgumentException")
    void findSlots_invalidTimezone_throwsException() {
        assertThatThrownBy(() -> slotQueryService.getAvailableSlots(
                doctor.getId(), LocalDate.parse("2026-07-28"), "Not/AZone", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid timezone parameter: Not/AZone");
    }

    @Test
    @DisplayName("findAvailableSlots: filter by appointment type works correctly via single query join")
    void findSlots_withTypeFilter_filtersCorrectly() {
        List<SlotResponseDTO> slots = slotQueryService.getAvailableSlots(
                doctor.getId(), LocalDate.parse("2026-07-28"), "Asia/Kolkata", "FOLLOW_UP");

        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).getStartTime().getHour()).isEqualTo(16); // Only the 4 PM slot
    }
}
