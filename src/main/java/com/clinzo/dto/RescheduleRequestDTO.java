package com.clinzo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RescheduleRequestDTO {

    @NotNull(message = "newSlotId is required")
    private Long newSlotId;
}
