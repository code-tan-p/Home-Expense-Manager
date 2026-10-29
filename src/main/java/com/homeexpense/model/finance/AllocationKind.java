package com.homeexpense.model.finance;

/** Why an allocation exists. */
public enum AllocationKind {
    AUTO_OLDEST_FIRST,
    EXPLICIT,
    EXCESS_TO_ADVANCE,
    OVERPAYMENT_TO_ADVANCE,
    KEPT_AFTER_REALLOCATION
}
