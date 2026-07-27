package com.clinzo.service;

import com.clinzo.domain.Doctor;
import com.clinzo.domain.Slot;
import com.clinzo.dto.SlotResponseDTO;
import com.clinzo.repository.DoctorRepository;
import com.clinzo.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlotQueryService {

    private final SlotRepository slotRepository;
    private final DoctorRepository doctorRepository;

    @Transactional(readOnly = true)
    public List<SlotResponseDTO> getAvailableSlots(Long doctorId, LocalDate date, String timezone, String appointmentType) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new IllegalArgumentException("Doctor not found with ID: " + doctorId));

        ZoneId targetZone;
        try {
            if (timezone != null && !timezone.trim().isEmpty()) {
                targetZone = ZoneId.of(timezone);
            } else {
                targetZone = ZoneId.of(doctor.getTimezone());
            }
        } catch (DateTimeException e) {
            log.warn("Invalid timezone provided: {}", timezone);
            // Must trigger a 400 Bad Request, our GlobalExceptionHandler handles IllegalArgumentException
            throw new IllegalArgumentException("Invalid timezone parameter: " + timezone);
        }

        // Calculate start of day and start of NEXT day in the target timezone
        ZonedDateTime startOfDay = date.atStartOfDay(targetZone);
        ZonedDateTime startOfNextDay = startOfDay.plusDays(1);

        // Convert boundaries to UTC Instants
        Instant fromUtc = startOfDay.toInstant();
        Instant toUtc = startOfNextDay.toInstant();

        log.debug("Querying slots for doctor {} between {} and {} (UTC)", doctorId, fromUtc, toUtc);

        List<Slot> slots = slotRepository.findAvailableSlotsWithFilters(doctorId, fromUtc, toUtc, appointmentType);

        return slots.stream()
                .map(slot -> SlotResponseDTO.builder()
                        .id(slot.getId())
                        .doctorId(slot.getDoctorId())
                        .startTime(slot.getStartTimeUtc().atZone(targetZone))
                        .endTime(slot.getEndTimeUtc().atZone(targetZone))
                        .status(slot.getStatus())
                        .build())
                .collect(Collectors.toList());
    }
}
