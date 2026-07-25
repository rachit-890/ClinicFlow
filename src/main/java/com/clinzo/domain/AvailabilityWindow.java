package com.clinzo.domain;

import com.clinzo.validation.AvailabilityWindowValid;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "availability_windows")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@AvailabilityWindowValid
public class AvailabilityWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    /** ISO day-of-week: 1=MON … 7=SUN. Set when is_recurring=true. */
    @Column(name = "day_of_week")
    private Short dayOfWeek;

    /** Specific date. Set when is_recurring=false. */
    @Column(name = "specific_date")
    private LocalDate specificDate;

    @Column(name = "start_time_utc", nullable = false)
    private Instant startTimeUtc;

    @Column(name = "end_time_utc", nullable = false)
    private Instant endTimeUtc;

    @Column(name = "slot_duration_minutes", nullable = false)
    private Integer slotDurationMinutes;

    @Column(name = "buffer_minutes", nullable = false)
    private Integer bufferMinutes;

    @Column(name = "appointment_type", nullable = false)
    private String appointmentType;

    @Column(name = "is_recurring", nullable = false)
    private Boolean isRecurring;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.active == null) this.active = true;
        if (this.bufferMinutes == null) this.bufferMinutes = 0;
        if (this.appointmentType == null) this.appointmentType = "GENERAL";
        if (this.isRecurring == null) this.isRecurring = true;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
