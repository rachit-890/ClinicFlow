package com.clinzo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/**
 * Validates that an AvailabilityWindow has exactly one of (dayOfWeek, specificDate)
 * set, consistent with its isRecurring flag.
 *
 * Mirrors the DB CHECK constraint chk_recurring_or_specific:
 *   is_recurring=true  → dayOfWeek IS NOT NULL AND specificDate IS NULL
 *   is_recurring=false → specificDate IS NOT NULL AND dayOfWeek IS NULL
 */
@Documented
@Constraint(validatedBy = AvailabilityWindowValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AvailabilityWindowValid {
    String message() default "Invalid availability window: is_recurring=true requires day_of_week (no specific_date), is_recurring=false requires specific_date (no day_of_week)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
