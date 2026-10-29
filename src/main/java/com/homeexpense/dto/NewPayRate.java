package com.homeexpense.dto;

import java.time.LocalDate;

import com.homeexpense.model.RateType;

/**
 * A new pay rate version. The amount is entered in rupees as text, for example "115.38".
 * Leave taskId and shiftId empty for the worker's standard rate of this type.
 */
public record NewPayRate(Long workerId, RateType rateType, Long taskId, Long shiftId, String unitLabel,
                         String amountRupees, LocalDate effectiveFrom, String note) {

    public static NewPayRate standard(Long workerId, RateType rateType, String amountRupees, LocalDate effectiveFrom) {
        return new NewPayRate(workerId, rateType, null, null, null, amountRupees, effectiveFrom, null);
    }
}
