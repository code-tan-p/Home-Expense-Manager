package com.homeexpense.model.earnings;

/** Which rate type an earning line was calculated with. */
public enum EarningLineType {
    MONTHLY_SALARY,
    PER_TASK,
    HOURLY,
    PER_UNIT,
    PER_DAY,
    PER_SHIFT,
    EXTRA_TASK,
    EXTRA_HOUR,
    EXTRA_DAY
}
