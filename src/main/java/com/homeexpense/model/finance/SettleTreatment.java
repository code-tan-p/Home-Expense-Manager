package com.homeexpense.model.finance;

/** What was decided about a non-zero balance when a version was settled (D6, D9). */
public enum SettleTreatment {
    NONE,
    CARRY_FORWARD,
    LEAVE_OUTSTANDING,
    OVERPAYMENT_TO_ADVANCE,
    OVERPAYMENT_CARRY,
    OVERPAYMENT_PENDING
}
