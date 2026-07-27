package com.clinzo.service;

import com.clinzo.AbstractIntegrationTest;
import com.clinzo.domain.AvailabilityWindow;
import com.clinzo.domain.Doctor;
import com.clinzo.domain.Slot;
import com.clinzo.domain.SlotStatus;
import com.clinzo.dto.BookingRequestDTO;
import com.clinzo.dto.BookingResponseDTO;
import com.clinzo.dto.HoldResponseDTO;
import com.clinzo.exception.SlotConflictException;
import com.clinzo.repository.AvailabilityWindowRepository;
import com.clinzo.repository.BookingRepository;
import com.clinzo.repository.DoctorRepository;
import com.clinzo.repository.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for HoldService and token-verified confirmation.
 */
@SpringBootTest
@ActiveProfiles("test")
class HoldServiceTest extends AbstractIntegrationTest {

    @Autowired private HoldService holdService;
    @Autowired private BookingService bookingService;
    @Autowired private DoctorRepository doctorRepository;
    @org.springframework.boot.test.mock.mockito.SpyBean
    private SlotRepository slotRepository;

    @Autowired private BookingRepository bookingRepository;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private AvailabilityWindowRepository windowRepository;

    private Slot targetSlot;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
        windowRepository.deleteAll();
        doctorRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        Doctor doctor = doctorRepository.save(Doctor.builder()
                .name("Dr. Hold")
                .timezone("UTC")
                .build());

        AvailabilityWindow window = windowRepository.save(AvailabilityWindow.builder()
                .doctorId(doctor.getId())
                .dayOfWeek((short) 1)
                .isRecurring(true)
                .startTimeUtc(Instant.parse("2026-08-01T11:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T11:30:00Z"))
                .slotDurationMinutes(30)
                .bufferMinutes(0)
                .appointmentType("GENERAL")
                .active(true)
                .build());

        targetSlot = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T11:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T11:30:00Z"))
                .status(SlotStatus.AVAILABLE)
                .version(0)
                .build());
    }

    @Test
    @DisplayName("Hold slot successfully generates opaque hold token and stores in Redis with TTL")
    void holdSlot_success() {
        HoldResponseDTO holdResponse = holdService.holdSlot(targetSlot.getId(), "patient-101");

        assertThat(holdResponse.getHoldToken()).isNotBlank();
        assertThat(holdResponse.getSlotId()).isEqualTo(targetSlot.getId());

        // Verify Redis key
        String redisToken = redisTemplate.opsForValue().get("hold:" + targetSlot.getId());
        assertThat(redisToken).isEqualTo(holdResponse.getHoldToken());

        // Verify DB slot status is HELD
        Slot slot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.HELD);
    }

    @Test
    @DisplayName("Attempting to hold an already held slot fails with 409 Conflict")
    void holdSlot_alreadyHeld_throwsConflict() {
        holdService.holdSlot(targetSlot.getId(), "patient-101");

        assertThatThrownBy(() -> holdService.holdSlot(targetSlot.getId(), "patient-102"))
                .isInstanceOf(SlotConflictException.class)
                .hasMessageContaining("not available for hold");
    }

    @Test
    @DisplayName("Confirm booking with valid hold token succeeds and evicts Redis hold")
    void confirmBooking_validHoldToken_success() {
        HoldResponseDTO hold = holdService.holdSlot(targetSlot.getId(), "patient-101");

        BookingResponseDTO booking = bookingService.confirmBooking(
                BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId("patient-101")
                        .holdToken(hold.getHoldToken())
                        .build()
        );

        assertThat(booking.getBookingId()).isNotNull();

        // Verify Redis key deleted after confirmation
        assertThat(redisTemplate.opsForValue().get("hold:" + targetSlot.getId())).isNull();

        // Verify DB slot status is BOOKED
        Slot slot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    @DisplayName("Confirm booking with wrong hold token rejected with 409 Conflict without updating DB")
    void confirmBooking_wrongHoldToken_rejected() {
        holdService.holdSlot(targetSlot.getId(), "patient-101");

        assertThatThrownBy(() -> bookingService.confirmBooking(
                BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId("patient-102")
                        .holdToken("fake-token-12345")
                        .build()
        )).isInstanceOf(SlotConflictException.class)
          .hasMessageContaining("Invalid or expired hold token");

        // Verify DB slot status is still HELD
        Slot slot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.HELD);
    }

    @Test
    @DisplayName("Confirm booking with expired hold token (removed from Redis) rejected")
    void confirmBooking_expiredHoldToken_rejected() {
        HoldResponseDTO hold = holdService.holdSlot(targetSlot.getId(), "patient-101");

        // Simulate TTL expiration in Redis
        redisTemplate.delete("hold:" + targetSlot.getId());

        assertThatThrownBy(() -> bookingService.confirmBooking(
                BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId("patient-101")
                        .holdToken(hold.getHoldToken())
                        .build()
        )).isInstanceOf(SlotConflictException.class)
          .hasMessageContaining("Invalid or expired hold token");
    }

    @Test
    @DisplayName("Orphaned Redis key handling: if DB update fails (0 rows affected), Redis key is deleted immediately")
    void holdSlot_dbUpdateFails_deletesOrphanedRedisKey() {
        org.mockito.Mockito.doReturn(0).when(slotRepository)
                .updateStatusWithOptimisticLock(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );

        assertThatThrownBy(() -> holdService.holdSlot(targetSlot.getId(), "patient-101"))
                .isInstanceOf(SlotConflictException.class)
                .hasMessageContaining("Concurrent modification");

        // Verify Redis key is deleted and NOT left orphaned
        String redisToken = redisTemplate.opsForValue().get("hold:" + targetSlot.getId());
        assertThat(redisToken).isNull();
    }
}
