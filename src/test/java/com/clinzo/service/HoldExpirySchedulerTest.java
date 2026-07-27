package com.clinzo.service;

import com.clinzo.AbstractIntegrationTest;
import com.clinzo.domain.AvailabilityWindow;
import com.clinzo.domain.Booking;
import com.clinzo.domain.BookingStatus;
import com.clinzo.domain.Doctor;
import com.clinzo.domain.Slot;
import com.clinzo.domain.SlotStatus;
import com.clinzo.dto.BookingRequestDTO;
import com.clinzo.repository.AuditLogRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class HoldExpirySchedulerTest extends AbstractIntegrationTest {

    @Autowired private HoldExpiryScheduler scheduler;
    @Autowired private BookingService bookingService;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AvailabilityWindowRepository windowRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    private Slot targetSlot;
    private String holdToken = "token-12345";

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
        windowRepository.deleteAll();
        doctorRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        Doctor doctor = doctorRepository.save(Doctor.builder()
                .name("Dr. Scheduler")
                .timezone("UTC")
                .build());

        AvailabilityWindow window = windowRepository.save(AvailabilityWindow.builder()
                .doctorId(doctor.getId())
                .dayOfWeek((short) 1)
                .isRecurring(true)
                .startTimeUtc(Instant.parse("2026-08-01T10:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T10:30:00Z"))
                .slotDurationMinutes(30)
                .bufferMinutes(0)
                .appointmentType("GENERAL")
                .active(true)
                .build());

        targetSlot = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T10:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T10:30:00Z"))
                .status(SlotStatus.HELD) // Starts as held
                .version(0)
                .build());

        bookingRepository.save(Booking.builder()
                .slotId(targetSlot.getId())
                .patientId("patient-1")
                .status(BookingStatus.HELD)
                .holdToken(holdToken)
                .holdExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES)) // Already expired
                .build());
                
        redisTemplate.opsForValue().set(BookingService.HOLD_KEY_PREFIX + targetSlot.getId(), holdToken);
    }

    @Test
    @DisplayName("Basic Sweep: Expired hold is successfully reverted to AVAILABLE and audited")
    void basicSweep_expiredHoldIsReverted() {
        List<Booking> expiredHolds = bookingRepository.findExpiredHolds(Instant.now(), BookingStatus.HELD, SlotStatus.HELD);
        assertThat(expiredHolds).hasSize(1);

        scheduler.sweepExpiredHolds();

        Slot updatedSlot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(updatedSlot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);

        Booking updatedBooking = bookingRepository.findBySlotIdAndHoldToken(targetSlot.getId(), holdToken).orElseThrow();
        assertThat(updatedBooking.getStatus()).isEqualTo(BookingStatus.EXPIRED);

        Boolean hasKey = redisTemplate.hasKey(BookingService.HOLD_KEY_PREFIX + targetSlot.getId());
        assertThat(hasKey).isFalse();

        long auditCount = auditLogRepository.count();
        assertThat(auditCount).isEqualTo(1);
        var auditLog = auditLogRepository.findAll().get(0);
        assertThat(auditLog.getAction()).isEqualTo("HOLD_EXPIRED");
        assertThat(auditLog.getOldState()).isEqualTo("HELD");
        assertThat(auditLog.getNewState()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("No-op Sweep: Slot won by confirmBooking before sweep evaluates 0 rows without exception")
    void sweepNoOp_whenSlotAlreadyConfirmed() {
        BookingRequestDTO confirmReq = BookingRequestDTO.builder()
                .slotId(targetSlot.getId())
                .patientId("patient-1")
                .holdToken(holdToken)
                .build();
        bookingService.confirmBooking(confirmReq);

        long initialAuditCount = auditLogRepository.count(); // 1 from confirm

        // Now, slot is BOOKED, but let's trick the sweep into querying it by resetting the booking
        Booking booking = bookingRepository.findBySlotIdAndHoldToken(targetSlot.getId(), holdToken).orElseThrow();
        booking.setStatus(BookingStatus.HELD);
        bookingRepository.save(booking);

        // Run the sweep
        scheduler.sweepExpiredHolds();

        // Should be a no-op! No new audit logs.
        assertThat(auditLogRepository.count()).isEqualTo(initialAuditCount);
        
        Slot updatedSlot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(updatedSlot.getStatus()).isEqualTo(SlotStatus.BOOKED); // Remains booked
    }

    @Test
    @DisplayName("Concurrency Race: Sweep vs Confirm on the exact same expired hold")
    void sweepVsConfirmRace() throws InterruptedException {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        
        AtomicInteger confirmSuccess = new AtomicInteger(0);
        AtomicInteger confirmConflict = new AtomicInteger(0);

        // Thread 1: Scheduler Sweep
        executor.submit(() -> {
            try {
                latch.await();
                scheduler.sweepExpiredHolds();
            } catch (Exception e) {
                // Ignore
            }
        });

        // Thread 2: Confirm Booking
        executor.submit(() -> {
            try {
                latch.await();
                BookingRequestDTO confirmReq = BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId("patient-1")
                        .holdToken(holdToken)
                        .build();
                bookingService.confirmBooking(confirmReq);
                confirmSuccess.incrementAndGet();
            } catch (Exception e) {
                confirmConflict.incrementAndGet();
            }
        });

        latch.countDown(); // Go!
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Either Confirm won (Success=1, Conflict=0, status=BOOKED), or Sweep won (Success=0, Conflict=1, status=AVAILABLE)
        Slot finalSlot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        
        if (confirmSuccess.get() == 1) {
            assertThat(finalSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
            assertThat(confirmConflict.get()).isEqualTo(0);
        } else {
            assertThat(finalSlot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
            assertThat(confirmConflict.get()).isEqualTo(1);
        }
    }
}
