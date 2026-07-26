package com.clinzo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldResponseDTO {
    private Long slotId;
    private String patientId;
    private String holdToken;
    private Instant expiresAt;
}
