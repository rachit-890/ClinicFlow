package com.clinzo.dto;

import lombok.*;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateAvailabilityWindowResponseDTO {

    private Long windowId;
    private Long doctorId;
    private Instant startTimeUtc;
    private Instant endTimeUtc;
    private Boolean active;
    private int prunedSlotsCount;
    private List<String> warnings;
}
