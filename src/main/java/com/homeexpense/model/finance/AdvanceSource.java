package com.homeexpense.model.finance;

/** Where an advance came from. Advances only ever come from real money paid, or from a shortfall declared as owed. */
public enum AdvanceSource {
    PAYMENT_EXCESS,
    OVERPAYMENT,
    REMAINDER
}
