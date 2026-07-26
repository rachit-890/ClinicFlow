package com.clinzo.service;

import com.clinzo.AbstractIntegrationTest;
import com.clinzo.domain.AvailabilityWindow;
import com.clinzo.domain.Doctor;
import com.clinzo.domain.Slot;
import com.clinzo.domain.SlotStatus;
import com.clinzo.dto.BookingRequestDTO;
import com.clinzo.dto.BookingResponseDTO;
import com.clinzo.exception.SlotConflictException;
import com.clinzo.repository.BookingRepository;
import com.clinzo.repository.DoctorRepository;
import com.clinzo.repository.SlotRepository;
import com.clinzo.repository.AvailabilityWindowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 50-thread concurrent booking test against a real Postgres + Redis Testcontainers setup.
 * Proves optimistic-locking exactly-one-winner semantics under extreme contention.
 */
@SpringBootTest
@ActiveProfiles("test")
class BookingServiceConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private BookingService bookingService;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AvailabilityWindowRepository windowRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;

    private Slot targetSlot;

    @BeforeEach
    void setUp() {
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
        windowRepository.deleteAll();
        doctorRepository.deleteAll();

        Doctor doctor = doctorRepository.save(Doctor.builder()
                .name("Dr. Concurrent")
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
                .status(SlotStatus.AVAILABLE)
                .version(0)
                .build());
    }

    @Test
    @DisplayName("50 concurrent threads attempting to book the exact same slot -> exactly 1 winner, 49 conflicts")
    void highContention_50Threads_exactlyOneWinner() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<BookingResponseDTO> successfulBookings = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String patientId = "patient-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // wait for start signal
                    BookingResponseDTO response = bookingService.confirmBooking(
                            BookingRequestDTO.builder()
                                    .slotId(targetSlot.getId())
                                    .patientId(patientId)
                                    .build()
                    );
                    successCount.incrementAndGet();
                    successfulBookings.add(response);
                } catch (SlotConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all 50 threads simultaneously
        boolean finished = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(49);

        // Verify DB State
        Slot updatedSlot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(updatedSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(updatedSlot.getVersion()).isEqualTo(1);

        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(bookingRepository.findAll().get(0).getPatientId())
                .isEqualTo(successfulBookings.get(0).getPatientId());
    }
}
