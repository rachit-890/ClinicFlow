package com.clinzo.service;

import com.clinzo.AbstractIntegrationTest;
import com.clinzo.domain.AvailabilityWindow;
import com.clinzo.domain.Booking;
import com.clinzo.domain.BookingStatus;
import com.clinzo.domain.Doctor;
import com.clinzo.domain.Slot;
import com.clinzo.domain.SlotStatus;
import com.clinzo.dto.BookingRequestDTO;
import com.clinzo.dto.BookingResponseDTO;
import com.clinzo.dto.HoldResponseDTO;
import com.clinzo.dto.UpdateAvailabilityWindowRequestDTO;
import com.clinzo.dto.UpdateAvailabilityWindowResponseDTO;
import com.clinzo.exception.SlotConflictException;
import com.clinzo.domain.AuditLog;
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
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 50-thread concurrent integration test suite against a real Postgres + Redis Testcontainers setup.
 * Proves atomic hold-race and confirm-race optimistic locking semantics under extreme contention.
 */
@SpringBootTest
@ActiveProfiles("test")
class BookingServiceConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private BookingService bookingService;
    @Autowired private HoldService holdService;
    @Autowired private AvailabilityService availabilityService;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private AvailabilityWindowRepository windowRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    private Slot targetSlot;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
        windowRepository.deleteAll();
        doctorRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

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
    @DisplayName("Test 1: 50 concurrent direct confirmBooking calls (no token) -> exactly 1 winner, 49 conflicts")
    void highContention_50Threads_directBooking_exactlyOneWinner() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<BookingResponseDTO> successfulBookings = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String patientId = "patient-direct-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
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

        startLatch.countDown();
        boolean finished = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(49);

        Slot updatedSlot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(updatedSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(updatedSlot.getVersion()).isEqualTo(1);

        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(bookingRepository.findAll().get(0).getPatientId())
                .isEqualTo(successfulBookings.get(0).getPatientId());
    }

    @Test
    @DisplayName("Test 2: 50 concurrent holdSlot calls -> exactly 1 winner gets holdToken, 49 conflicts, slot HELD")
    void highContention_50Threads_concurrentHold_exactlyOneWinner() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<HoldResponseDTO> successfulHolds = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String patientId = "patient-hold-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    HoldResponseDTO response = holdService.holdSlot(targetSlot.getId(), patientId);
                    successCount.incrementAndGet();
                    successfulHolds.add(response);
                } catch (SlotConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(49);

        // Verify DB State: HELD, version 1
        Slot updatedSlot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(updatedSlot.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(updatedSlot.getVersion()).isEqualTo(1);

        // Exactly 1 HELD Booking row
        List<Booking> bookings = bookingRepository.findAll();
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(bookings.get(0).getPatientId()).isEqualTo(successfulHolds.get(0).getPatientId());

        // Exactly 1 Redis key present matching holdToken
        String storedRedisToken = redisTemplate.opsForValue().get("hold:" + targetSlot.getId());
        assertThat(storedRedisToken).isNotNull().isEqualTo(successfulHolds.get(0).getHoldToken());
    }

    @Test
    @DisplayName("Test 3: 50 threads race to holdSlot -> 1 hold winner, 49 fail; hold winner confirms -> slot BOOKED")
    void highContention_50Threads_holdThenConfirm_patientFlow() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger holdSuccessCount = new AtomicInteger(0);
        AtomicInteger holdConflictCount = new AtomicInteger(0);
        List<HoldResponseDTO> successfulHolds = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String patientId = "patient-flow-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    HoldResponseDTO holdResponse = holdService.holdSlot(targetSlot.getId(), patientId);
                    holdSuccessCount.incrementAndGet();
                    successfulHolds.add(holdResponse);
                } catch (SlotConflictException e) {
                    holdConflictCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        // Step A Assertions: Exactly 1 thread receives valid token; 49 fail at hold step
        assertThat(holdSuccessCount.get()).isEqualTo(1);
        assertThat(holdConflictCount.get()).isEqualTo(49);
        assertThat(successfulHolds).hasSize(1);

        HoldResponseDTO winningHold = successfulHolds.get(0);

        // Step B: The single winning thread calls confirmBooking with its token
        BookingResponseDTO confirmResponse = bookingService.confirmBooking(
                BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId(winningHold.getPatientId())
                        .holdToken(winningHold.getHoldToken())
                        .build()
        );

        assertThat(confirmResponse.getBookingId()).isNotNull();
        assertThat(confirmResponse.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        // Step C Assertions: Slot ends BOOKED with version 2 (0->1 hold, 1->2 confirm), 1 CONFIRMED booking
        Slot updatedSlot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(updatedSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(updatedSlot.getVersion()).isEqualTo(2);

        List<Booking> bookings = bookingRepository.findAll();
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(bookings.get(0).getPatientId()).isEqualTo(winningHold.getPatientId());

        // Redis key evicted after confirmation
        assertThat(redisTemplate.opsForValue().get("hold:" + targetSlot.getId())).isNull();
    }

    @Test
    @DisplayName("Test 4: Mixed-path race — 25 holdSlot threads vs 25 confirmBooking (no token) threads -> exactly 1 winner")
    void highContention_50Threads_mixedPath_25Hold_25DirectConfirm_exactlyOneWinner() throws InterruptedException {
        int totalThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);

        AtomicInteger holdWins = new AtomicInteger(0);
        AtomicInteger confirmWins = new AtomicInteger(0);
        AtomicInteger totalConflicts = new AtomicInteger(0);

        // 25 threads for holdSlot
        for (int i = 0; i < 25; i++) {
            final String patientId = "patient-mixed-hold-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    holdService.holdSlot(targetSlot.getId(), patientId);
                    holdWins.incrementAndGet();
                } catch (SlotConflictException e) {
                    totalConflicts.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 25 threads for confirmBooking without token
        for (int i = 0; i < 25; i++) {
            final String patientId = "patient-mixed-confirm-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    bookingService.confirmBooking(
                            BookingRequestDTO.builder()
                                    .slotId(targetSlot.getId())
                                    .patientId(patientId)
                                    .build()
                    );
                    confirmWins.incrementAndGet();
                } catch (SlotConflictException e) {
                    totalConflicts.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // simultaneous release of all 50 threads
        boolean finished = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();

        // Combined assertions across the entire 50-thread pool
        int totalSuccesses = holdWins.get() + confirmWins.get();
        assertThat(totalSuccesses).isEqualTo(1);
        assertThat(totalConflicts.get()).isEqualTo(49);

        // Verify final DB state
        Slot finalSlot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(finalSlot.getVersion()).isEqualTo(1);
        assertThat(bookingRepository.count()).isEqualTo(1);

        if (holdWins.get() == 1) {
            assertThat(finalSlot.getStatus()).isEqualTo(SlotStatus.HELD);
            assertThat(redisTemplate.opsForValue().get("hold:" + targetSlot.getId())).isNotNull();
        } else {
            assertThat(finalSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        }
    }

    @Test
    @DisplayName("Test 5: Direct confirm (no token) on a HELD slot fails with 409, leaving hold undisturbed")
    void confirmBooking_onHeldSlot_withoutToken_rejected() {
        // Step A: Patient A holds slot
        HoldResponseDTO holdA = holdService.holdSlot(targetSlot.getId(), "patient-A");
        assertThat(holdA.getHoldToken()).isNotBlank();

        // Verify slot is HELD
        Slot slotAfterHold = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(slotAfterHold.getStatus()).isEqualTo(SlotStatus.HELD);

        // Step B: Patient B attempts direct confirm with NO token
        assertThatThrownBy(() -> bookingService.confirmBooking(
                BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId("patient-B")
                        .build()
        )).isInstanceOf(SlotConflictException.class)
          .hasMessageContaining("Slot " + targetSlot.getId() + " is currently on hold");

        // Step C: Verify Patient A's hold is undisturbed and slot remains HELD
        Slot slotAfterAttempt = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(slotAfterAttempt.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(slotAfterAttempt.getVersion()).isEqualTo(1);

        String redisToken = redisTemplate.opsForValue().get("hold:" + targetSlot.getId());
        assertThat(redisToken).isEqualTo(holdA.getHoldToken());

        List<Booking> bookings = bookingRepository.findAll();
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getPatientId()).isEqualTo("patient-A");
        assertThat(bookings.get(0).getStatus()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    @DisplayName("Test 6: Concurrent reschedule collision — 2 patients reschedule into same target slot -> 1 winner, 1 failed (rollback verified)")
    void highContention_2Patients_concurrentReschedule_toSameSlot_exactlyOneWinner() throws InterruptedException {
        Doctor doctor = doctorRepository.findAll().get(0);
        AvailabilityWindow window = windowRepository.findAll().get(0);

        Slot slot1 = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T11:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T11:30:00Z"))
                .status(SlotStatus.BOOKED)
                .version(1)
                .build());

        Slot slot2 = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T12:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T12:30:00Z"))
                .status(SlotStatus.BOOKED)
                .version(1)
                .build());

        Booking booking1 = bookingRepository.save(Booking.builder()
                .slotId(slot1.getId())
                .patientId("patient-resched-1")
                .status(BookingStatus.CONFIRMED)
                .build());

        Booking booking2 = bookingRepository.save(Booking.builder()
                .slotId(slot2.getId())
                .patientId("patient-resched-2")
                .status(BookingStatus.CONFIRMED)
                .build());

        Slot targetNewSlot = targetSlot; // targetSlot is AVAILABLE from setUp()

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                bookingService.rescheduleBooking(booking1.getId(), targetNewSlot.getId());
                successCount.incrementAndGet();
            } catch (SlotConflictException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                endLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                bookingService.rescheduleBooking(booking2.getId(), targetNewSlot.getId());
                successCount.incrementAndGet();
            } catch (SlotConflictException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean finished = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        Slot updatedTarget = slotRepository.findById(targetNewSlot.getId()).orElseThrow();
        assertThat(updatedTarget.getStatus()).isEqualTo(SlotStatus.BOOKED);

        Booking b1Current = bookingRepository.findById(booking1.getId()).orElseThrow();
        Booking b2Current = bookingRepository.findById(booking2.getId()).orElseThrow();

        if (b1Current.getStatus() == BookingStatus.RESCHEDULED) {
            assertThat(slotRepository.findById(slot1.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
            assertThat(b2Current.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(slotRepository.findById(slot2.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.BOOKED);
        } else {
            assertThat(slotRepository.findById(slot2.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
            assertThat(b1Current.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(slotRepository.findById(slot1.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.BOOKED);
        }
    }

    @Test
    @DisplayName("Test 7: cancelBooking immediately frees slot as AVAILABLE and writes AuditLog")
    void cancelBooking_immediatelyFreesSlotAsAvailable_andWritesAuditLog() {
        BookingResponseDTO confirmed = bookingService.confirmBooking(
                BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId("patient-cancel-1")
                        .build()
        );

        BookingResponseDTO cancelled = bookingService.cancelBooking(confirmed.getBookingId());

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);

        Slot slot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);

        List<AuditLog> auditLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDesc("BOOKING", confirmed.getBookingId());
        assertThat(auditLogs).hasSize(2);
        assertThat(auditLogs.get(0).getAction()).isEqualTo("CANCEL");
        assertThat(auditLogs.get(0).getNewState()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Test 8: cancelBooking on already CANCELLED booking is rejected with 409 Conflict")
    void cancelBooking_alreadyCancelledOrInvalidStatus_rejected() {
        BookingResponseDTO confirmed = bookingService.confirmBooking(
                BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId("patient-cancel-2")
                        .build()
        );

        bookingService.cancelBooking(confirmed.getBookingId());

        assertThatThrownBy(() -> bookingService.cancelBooking(confirmed.getBookingId()))
                .isInstanceOf(SlotConflictException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    @Test
    @DisplayName("Test 9: rescheduleBooking success frees old slot, creates new booking, and writes dual AuditLogs")
    void rescheduleBooking_success_freesOldSlot_createsNewBooking_andDualAuditLogs() {
        Doctor doctor = doctorRepository.findAll().get(0);
        AvailabilityWindow window = windowRepository.findAll().get(0);

        Slot newSlot = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T14:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T14:30:00Z"))
                .status(SlotStatus.AVAILABLE)
                .version(0)
                .build());

        BookingResponseDTO initial = bookingService.confirmBooking(
                BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId("patient-resched-3")
                        .build()
        );

        BookingResponseDTO rescheduled = bookingService.rescheduleBooking(initial.getBookingId(), newSlot.getId());

        assertThat(rescheduled.getSlotId()).isEqualTo(newSlot.getId());
        assertThat(rescheduled.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        assertThat(slotRepository.findById(targetSlot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(slotRepository.findById(newSlot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.BOOKED);

        Booking oldBooking = bookingRepository.findById(initial.getBookingId()).orElseThrow();
        assertThat(oldBooking.getStatus()).isEqualTo(BookingStatus.RESCHEDULED);

        List<AuditLog> oldAuditLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDesc("BOOKING", initial.getBookingId());
        assertThat(oldAuditLogs).hasSize(2);
        assertThat(oldAuditLogs.get(0).getAction()).isEqualTo("RESCHEDULE_OUT");

        List<AuditLog> newAuditLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDesc("BOOKING", rescheduled.getBookingId());
        assertThat(newAuditLogs).hasSize(1);
        assertThat(newAuditLogs.get(0).getAction()).isEqualTo("RESCHEDULE_IN");
    }

    @Test
    @DisplayName("Test 10: rescheduleBooking to an already BOOKED target slot rolls back transaction cleanly")
    void rescheduleBooking_targetSlotTaken_rollsBackTransaction_leavesOldBookingConfirmed() {
        Doctor doctor = doctorRepository.findAll().get(0);
        AvailabilityWindow window = windowRepository.findAll().get(0);

        Slot takenSlot = slotRepository.save(Slot.builder()
                .doctorId(doctor.getId())
                .availabilityWindowId(window.getId())
                .startTimeUtc(Instant.parse("2026-08-01T15:00:00Z"))
                .endTimeUtc(Instant.parse("2026-08-01T15:30:00Z"))
                .status(SlotStatus.BOOKED)
                .version(1)
                .build());

        BookingResponseDTO initial = bookingService.confirmBooking(
                BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId("patient-resched-4")
                        .build()
        );

        assertThatThrownBy(() -> bookingService.rescheduleBooking(initial.getBookingId(), takenSlot.getId()))
                .isInstanceOf(SlotConflictException.class)
                .hasMessageContaining("Target slot " + takenSlot.getId() + " is already booked");

        Booking bookingAfter = bookingRepository.findById(initial.getBookingId()).orElseThrow();
        assertThat(bookingAfter.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        Slot slotAfter = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(slotAfter.getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    @DisplayName("Test 11: Concurrent double-cancel on the same bookingId -> 1 winner, 1 failed, 1 AuditLog entry")
    void concurrentDoubleCancel_onSameBooking_exactlyOneWinner() throws InterruptedException {
        BookingResponseDTO confirmed = bookingService.confirmBooking(
                BookingRequestDTO.builder()
                        .slotId(targetSlot.getId())
                        .patientId("patient-double-cancel")
                        .build()
        );

        Long bookingId = confirmed.getBookingId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                bookingService.cancelBooking(bookingId);
                successCount.incrementAndGet();
            } catch (SlotConflictException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                endLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                bookingService.cancelBooking(bookingId);
                successCount.incrementAndGet();
            } catch (SlotConflictException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean finished = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        Slot slot = slotRepository.findById(targetSlot.getId()).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);

        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);

        List<AuditLog> auditLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDesc("BOOKING", bookingId);
        assertThat(auditLogs).hasSize(2);
        assertThat(auditLogs.get(0).getAction()).isEqualTo("CANCEL");
    }

    @Test
    @DisplayName("Test 12: Concurrent availability window update (prune) vs confirmBooking on same slot")
    void concurrentWindowUpdate_and_confirmBooking() throws InterruptedException {
        Doctor doc = doctorRepository.findAll().get(0);
        AvailabilityWindow win = windowRepository.findAll().get(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);

        AtomicReference<UpdateAvailabilityWindowResponseDTO> updateResult = new AtomicReference<>();
        AtomicReference<BookingResponseDTO> bookingResult = new AtomicReference<>();
        AtomicReference<Exception> bookingException = new AtomicReference<>();

        executor.submit(() -> {
            try {
                startLatch.await();
                UpdateAvailabilityWindowRequestDTO req = UpdateAvailabilityWindowRequestDTO.builder()
                        .startTimeUtc(Instant.parse("2026-08-01T10:00:00Z"))
                        .endTimeUtc(Instant.parse("2026-08-01T10:15:00Z"))
                        .build();
                UpdateAvailabilityWindowResponseDTO resp = availabilityService.updateAvailabilityWindow(
                        doc.getId(), win.getId(), req);
                updateResult.set(resp);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                endLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                BookingResponseDTO resp = bookingService.confirmBooking(
                        BookingRequestDTO.builder()
                                .slotId(targetSlot.getId())
                                .patientId("patient-race-prune")
                                .build()
                );
                bookingResult.set(resp);
            } catch (Exception e) {
                bookingException.set(e);
            } finally {
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean finished = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();

        UpdateAvailabilityWindowResponseDTO windowResp = updateResult.get();
        assertThat(windowResp).isNotNull();

        if (bookingResult.get() != null) {
            assertThat(windowResp.getPrunedSlotsCount()).isEqualTo(0);
            assertThat(windowResp.getWarnings()).hasSize(1);
            assertThat(windowResp.getWarnings().get(0)).contains("Slot " + targetSlot.getId());

            Slot slot = slotRepository.findById(targetSlot.getId()).orElseThrow();
            assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        } else {
            assertThat(bookingException.get()).isInstanceOf(SlotConflictException.class);
            assertThat(windowResp.getPrunedSlotsCount()).isEqualTo(1);
            assertThat(windowResp.getWarnings()).isEmpty();

            assertThat(slotRepository.findById(targetSlot.getId())).isEmpty();
        }
    }

    @Test
    @DisplayName("Test 6: AuditLog transactional cohesion — failed confirmBooking does not write audit log")
    void transactionalCohesion_failedConfirmWritesNoAudit() {
        // Step 1: Pre-book the slot so the next confirm fails (optimistic lock / 0 rows)
        BookingRequestDTO successRequest = BookingRequestDTO.builder()
                .slotId(targetSlot.getId())
                .patientId("patient-first")
                .build();
        bookingService.confirmBooking(successRequest);
        
        // Assert exactly 1 audit log exists (from the success)
        long initialAuditCount = auditLogRepository.count();
        assertThat(initialAuditCount).isEqualTo(1);

        // Step 2: Try to book it again, forcing SlotConflictException
        BookingRequestDTO failRequest = BookingRequestDTO.builder()
                .slotId(targetSlot.getId())
                .patientId("patient-second")
                .build();

        assertThatThrownBy(() -> bookingService.confirmBooking(failRequest))
                .isInstanceOf(SlotConflictException.class);

        // Step 3: Verify NO additional audit log was written for the failed attempt
        assertThat(auditLogRepository.count()).isEqualTo(initialAuditCount);
    }
}
