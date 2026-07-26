package com.clinzo.dto;

import com.clinzo.domain.BookingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponseDTO {
    private Long bookingId;
    private Long slotId;
    private String patientId;
    private BookingStatus status;
    private Instant createdAt;
}
