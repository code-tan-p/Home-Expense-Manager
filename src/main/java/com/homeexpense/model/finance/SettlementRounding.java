package com.homeexpense.model.finance;

/** Settlement rounding of the net payable to whole rupees (CLAUDE.md section 10). EXACT is the default. */
public enum SettlementRounding {
    EXACT,
    UP,
    DOWN,
    NEAREST
}
