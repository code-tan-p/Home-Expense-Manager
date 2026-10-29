package com.homeexpense.model;

/**
 * What a {@link PayRate} pays for. The first six correspond to the primary payment models;
 * the EXTRA_ types are additional earning rules for separately agreed work.
 */
public enum RateType {
    MONTHLY_SALARY,
    PER_TASK,
    HOURLY,
    PER_UNIT,
    PER_DAY,
    PER_SHIFT,
    EXTRA_TASK,
    EXTRA_HOUR,
    EXTRA_DAY;

    public boolean isPrimary() {
        return !isAdditional();
    }

    public boolean isAdditional() {
        return this == EXTRA_TASK || this == EXTRA_HOUR || this == EXTRA_DAY;
    }

    public boolean allowsTask() {
        return this == PER_TASK || this == PER_UNIT || this == EXTRA_TASK;
    }

    public boolean requiresTask() {
        return this == EXTRA_TASK;
    }

    public boolean allowsShift() {
        return this == PER_SHIFT;
    }

    public boolean requiresUnit() {
        return this == PER_UNIT;
    }

    /**
     * The primary model this additional rule would duplicate, if any. An extra-task rule
     * under a per-task arrangement (and likewise extra hours under hourly pay, extra days
     * under per-day pay) would make the same work payable under two rules.
     */
    public PaymentModel conflictingPrimaryModel() {
        return switch (this) {
            case EXTRA_TASK -> PaymentModel.PER_TASK;
            case EXTRA_HOUR -> PaymentModel.HOURLY;
            case EXTRA_DAY -> PaymentModel.PER_DAY;
            default -> null;
        };
    }
}
