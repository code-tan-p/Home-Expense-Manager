package com.homeexpense.model;

/** The single primary way a worker is paid during a payment arrangement. */
public enum PaymentModel {
    MONTHLY_SALARY,
    PER_TASK,
    HOURLY,
    PER_UNIT,
    PER_DAY,
    PER_SHIFT;

    /** The rate type that holds this model's amounts. */
    public RateType rateType() {
        return RateType.valueOf(name());
    }
}
