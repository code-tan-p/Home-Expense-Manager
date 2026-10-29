package com.homeexpense.model.earnings;

/** Review state of a suggested or manual amount. NEEDS_REVIEW amounts are excluded until decided. */
public enum ReviewStatus {
    NONE,
    NEEDS_REVIEW,
    ACCEPTED,
    OVERRIDDEN
}
