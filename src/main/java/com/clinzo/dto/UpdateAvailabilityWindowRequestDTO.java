package com.clinzo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateAvailabilityWindowRequestDTO {

    @NotNull(message = "startTimeUtc is required")
    private Instant startTimeUtc;

    @NotNull(message = "endTimeUtc is required")
    private Instant endTimeUtc;

    private Short dayOfWeek;

    private Boolean isRecurring;

    private Integer slotDurationMinutes;

    private Integer bufferMinutes;

    private String appointmentType;
}
