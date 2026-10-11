package com.homeexpense.model.finance;

/** A line in a settlement version. UNPAID_TIME_INFO is informational and always zero (D11). */
public enum SettlementItemType {
    EARNINGS,
    ADJUSTMENT,
    SHORTFALL,
    ADVANCE_RECOVERY,
    ROUNDING,
    UNPAID_TIME_INFO
}
