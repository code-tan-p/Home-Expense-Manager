package com.homeexpense.service;

import java.time.LocalDate;

/**
 * Tells history-changing operations whether recorded earnings depend on the history they
 * would change. Effective-dated changes are only allowed where no earnings would be affected.
 */
public interface EarningsGuard {

    /** True if the worker has earnings recorded on or after the given date. */
    boolean hasEarningsOnOrAfter(Long workerId, LocalDate date);

    /** True if any recorded earning was calculated using this pay rate. */
    boolean isRateUsedByEarnings(Long payRateId);

    /** True if earnings have been recorded for the worker's work on this exact date. */
    boolean hasEarningsOn(Long workerId, LocalDate date);
}
