package com.homeexpense.model;

/** Why an expected activity was not done. */
public enum AbsenceReason {
    /** The household gave the worker the day or activity off. */
    HOUSEHOLD_HOLIDAY,
    /** Leave the worker asked for and the household approved. */
    APPROVED_LEAVE,
    /** The worker did not come and the absence was not approved. */
    UNAUTHORIZED_ABSENCE
}
