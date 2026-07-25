package com.clinzo.validation;

import com.clinzo.domain.AvailabilityWindow;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fix #3 verification: AvailabilityWindow bean validator mirrors the DB CHECK constraint.
 */
class AvailabilityWindowValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private AvailabilityWindow.AvailabilityWindowBuilder baseBuilder() {
        return AvailabilityWindow.builder()
                .doctorId(1L)
                .startTimeUtc(Instant.parse("2026-07-28T09:00:00Z"))
                .endTimeUtc(Instant.parse("2026-07-28T17:00:00Z"))
                .slotDurationMinutes(30)
                .bufferMinutes(5)
                .appointmentType("GENERAL")
                .active(true);
    }

    @Test
    @DisplayName("Valid recurring window: isRecurring=true, dayOfWeek set, specificDate null")
    void recurringWithDayOfWeek_valid() {
        AvailabilityWindow w = baseBuilder()
                .isRecurring(true)
                .dayOfWeek((short) 1)
                .specificDate(null)
                .build();

        Set<ConstraintViolation<AvailabilityWindow>> violations = validator.validate(w);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Valid one-off window: isRecurring=false, specificDate set, dayOfWeek null")
    void oneOffWithSpecificDate_valid() {
        AvailabilityWindow w = baseBuilder()
                .isRecurring(false)
                .dayOfWeek(null)
                .specificDate(LocalDate.of(2026, 7, 28))
                .build();

        Set<ConstraintViolation<AvailabilityWindow>> violations = validator.validate(w);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Invalid: isRecurring=true but specificDate set (should have dayOfWeek only)")
    void recurringWithSpecificDate_invalid() {
        AvailabilityWindow w = baseBuilder()
                .isRecurring(true)
                .dayOfWeek((short) 1)
                .specificDate(LocalDate.of(2026, 7, 28))
                .build();

        Set<ConstraintViolation<AvailabilityWindow>> violations = validator.validate(w);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("is_recurring");
    }

    @Test
    @DisplayName("Invalid: isRecurring=false but dayOfWeek set (should have specificDate only)")
    void oneOffWithDayOfWeek_invalid() {
        AvailabilityWindow w = baseBuilder()
                .isRecurring(false)
                .dayOfWeek((short) 3)
                .specificDate(null)
                .build();

        Set<ConstraintViolation<AvailabilityWindow>> violations = validator.validate(w);
        assertThat(violations).hasSize(1);
    }

    @Test
    @DisplayName("Invalid: isRecurring=true but no dayOfWeek and no specificDate")
    void recurringWithNeitherField_invalid() {
        AvailabilityWindow w = baseBuilder()
                .isRecurring(true)
                .dayOfWeek(null)
                .specificDate(null)
                .build();

        Set<ConstraintViolation<AvailabilityWindow>> violations = validator.validate(w);
        assertThat(violations).hasSize(1);
    }

    @Test
    @DisplayName("Invalid: isRecurring=false but both dayOfWeek and specificDate set")
    void oneOffWithBothFields_invalid() {
        AvailabilityWindow w = baseBuilder()
                .isRecurring(false)
                .dayOfWeek((short) 5)
                .specificDate(LocalDate.of(2026, 7, 28))
                .build();

        Set<ConstraintViolation<AvailabilityWindow>> violations = validator.validate(w);
        assertThat(violations).hasSize(1);
    }
}
