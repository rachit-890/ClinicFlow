package com.clinzo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingRequestDTO {
    @NotNull(message = "slotId is required")
    private Long slotId;

    @NotBlank(message = "patientId is required")
    private String patientId;

    /** Hold token issued during hold creation (required if confirming a hold). */
    private String holdToken;
}
