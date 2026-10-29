package com.homeexpense.model.earnings;

/** Why a line is payable. */
public enum EarningBasis {
    /** The agreed monthly salary for paid scheduled day-units. */
    SALARY,
    /** Scheduled work that was done. */
    WORK,
    /** A household-given holiday on a scheduled working day. */
    HOLIDAY,
    /** Unscheduled work: an additional activity or work on a regular day off. */
    EXTRA_WORK
}
