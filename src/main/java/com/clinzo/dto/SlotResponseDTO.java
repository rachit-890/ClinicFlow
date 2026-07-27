package com.clinzo.dto;

import com.clinzo.domain.SlotStatus;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@Builder
public class SlotResponseDTO {
    private Long id;
    private Long doctorId;
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;
    private SlotStatus status;
}
