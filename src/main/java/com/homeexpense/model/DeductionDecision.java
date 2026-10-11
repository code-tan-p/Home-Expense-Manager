package com.homeexpense.model;

/**
 * The household's decision about deducting pay for an unauthorized absence. No deduction may
 * be applied while the decision is PENDING. Amounts are not calculated here.
 */
public enum DeductionDecision {
    PENDING,
    APPROVED,
    WAIVED
}
