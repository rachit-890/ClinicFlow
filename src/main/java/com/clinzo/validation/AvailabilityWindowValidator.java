package com.clinzo.validation;

import com.clinzo.domain.AvailabilityWindow;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Enforces the same rule as the DB CHECK constraint chk_recurring_or_specific,
 * failing fast with a 400 before hitting the database.
 */
public class AvailabilityWindowValidator
        implements ConstraintValidator<AvailabilityWindowValid, AvailabilityWindow> {

    @Override
    public boolean isValid(AvailabilityWindow w, ConstraintValidatorContext ctx) {
        if (w == null) return true; // let @NotNull handle nulls

        Boolean recurring = w.getIsRecurring();
        if (recurring == null) return false;

        if (recurring) {
            // Recurring: must have dayOfWeek, must NOT have specificDate
            return w.getDayOfWeek() != null && w.getSpecificDate() == null;
        } else {
            // One-off: must have specificDate, must NOT have dayOfWeek
            return w.getSpecificDate() != null && w.getDayOfWeek() == null;
        }
    }
}
