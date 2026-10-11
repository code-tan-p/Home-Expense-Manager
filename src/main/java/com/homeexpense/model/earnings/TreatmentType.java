package com.homeexpense.model.earnings;

/** How one work record was treated in an earnings calculation. Every current record gets exactly one. */
public enum TreatmentType {
    /** Paid by its own or a shared earning line. */
    PAID,
    /** Done work that the monthly salary already covers. */
    COVERED_BY_SALARY,
    /** Covered by the single day-rate line for that date. */
    COVERED_BY_DAY_RATE,
    /** Covered by the single extra-day line for that date. */
    COVERED_BY_EXTRA_DAY,
    /** Household holiday on a scheduled day: paid (salary not reduced, or expected earnings paid). */
    HOLIDAY_PAID,
    /** Household holiday for a per-unit activity: nothing delivered, nothing paid. */
    HOLIDAY_NOT_PAID,
    /** Approved leave: unpaid. */
    UNPAID_LEAVE,
    /** Unauthorized absence of a non-salary worker: nothing was done, nothing is earned. */
    ABSENCE_UNPAID,
    /** Unauthorized absence of a salaried worker without a decision: salary is not reduced. */
    ABSENCE_PENDING_PAID,
    /** Unauthorized absence whose deduction was waived: salary is not reduced. */
    ABSENCE_WAIVED_PAID,
    /** Unauthorized absence whose deduction was approved: salary day-units are reduced. */
    ABSENCE_APPROVED_UNPAID,
    /** Partial work of a salaried worker: a suggested reduction that applies only once accepted or overridden. */
    SALARY_PARTIAL_REDUCTION,
    /** Paid by a line whose amount still needs the household's review. */
    NEEDS_REVIEW,
    /** No configured rate applies; nothing is paid and a warning is shown. */
    NO_APPLICABLE_RATE
}
