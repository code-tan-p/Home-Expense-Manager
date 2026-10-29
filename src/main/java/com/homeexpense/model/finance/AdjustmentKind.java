package com.homeexpense.model.finance;

/** Kinds of financial adjustment. REVERSAL mirrors an earlier adjustment with an opposite amount. */
public enum AdjustmentKind {
    CORRECTION,
    CARRY_FORWARD,
    OVERPAYMENT_CARRY,
    CARRY_CORRECTION,
    REMAINDER_CARRY,
    REMAINDER_TO_ADVANCE,
    REVERSAL
}
