package com.homeexpense.model.finance;

/** OPEN can be recalculated; SETTLED is a frozen snapshot; SUPERSEDED was replaced by reopening. */
public enum SettlementStatus {
    OPEN,
    SETTLED,
    SUPERSEDED
}
