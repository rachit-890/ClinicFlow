package com.clinzo.service;

import com.clinzo.domain.AvailabilityWindow;
import com.clinzo.domain.Slot;
import com.clinzo.domain.SlotStatus;
import com.clinzo.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Materializes availability windows into discrete bookable slots.
 *
 * Why materialized (not computed-on-read): we need a row to hold a unique
 * constraint / lock against. Computing slots in memory means two concurrent
 * booking requests have nothing shared to serialize on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlotGenerationService {

    private final SlotRepository slotRepository;

    /**
     * Generate slots for an availability window.
     * Splits [startTime, endTime) into chunks of slotDurationMinutes,
     * with bufferMinutes gaps between consecutive slots.
     *
     * Idempotent: duplicate (doctor_id, start_time_utc) pairs are silently
     * skipped via the DB unique constraint.
     *
     * @return list of slots that were successfully inserted (excludes duplicates)
     */
    @Transactional
    public List<Slot> generateSlots(AvailabilityWindow window) {
        if (!window.getActive()) {
            log.warn("Skipping slot generation for inactive window {}", window.getId());
            return List.of();
        }

        Duration slotDuration = Duration.ofMinutes(window.getSlotDurationMinutes());
        Duration buffer = Duration.ofMinutes(window.getBufferMinutes());

        List<Slot> generated = new ArrayList<>();
        Instant cursor = window.getStartTimeUtc();
        Instant windowEnd = window.getEndTimeUtc();

        while (true) {
            Instant slotEnd = cursor.plus(slotDuration);

            // The slot must fit entirely within the window
            if (slotEnd.isAfter(windowEnd)) {
                break;
            }

            Slot slot = Slot.builder()
                    .doctorId(window.getDoctorId())
                    .availabilityWindowId(window.getId())
                    .startTimeUtc(cursor)
                    .endTimeUtc(slotEnd)
                    .status(SlotStatus.AVAILABLE)
                    .version(0)
                    .build();

            generated.add(slot);

            // Move cursor past slot + buffer
            cursor = slotEnd.plus(buffer);
        }

        // Save all — duplicates hit the unique constraint and are ignored
        // We use saveAll which will throw on constraint violation,
        // so we save one-by-one and swallow duplicates for idempotency.
        List<Slot> saved = new ArrayList<>();
        for (Slot slot : generated) {
            try {
                saved.add(slotRepository.saveAndFlush(slot));
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                log.debug("Slot already exists for doctor {} at {} — skipped (idempotent)",
                        slot.getDoctorId(), slot.getStartTimeUtc());
            }
        }

        log.info("Generated {} slots for window {} (doctor {}), {} already existed",
                saved.size(), window.getId(), window.getDoctorId(),
                generated.size() - saved.size());

        return saved;
    }
}
