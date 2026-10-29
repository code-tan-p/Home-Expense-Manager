package com.homeexpense.service;

import java.time.LocalDate;

import com.homeexpense.repository.EarningLineRepository;
import com.homeexpense.repository.EarningRunRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link EarningsGuard} backed by finalized earnings. Draft calculations never lock anything:
 * they are recalculated from the current data. Database triggers in V8 enforce the same locks.
 */
@Component
@Transactional(readOnly = true)
public class FinalizedEarningsGuard implements EarningsGuard {

    private final EarningRunRepository runRepository;
    private final EarningLineRepository lineRepository;

    public FinalizedEarningsGuard(EarningRunRepository runRepository, EarningLineRepository lineRepository) {
        this.runRepository = runRepository;
        this.lineRepository = lineRepository;
    }

    @Override
    public boolean hasEarningsOnOrAfter(Long workerId, LocalDate date) {
        return runRepository.existsFinalizedEndingOnOrAfter(workerId, date);
    }

    @Override
    public boolean isRateUsedByEarnings(Long payRateId) {
        return lineRepository.existsFinalizedUsingRate(payRateId);
    }

    @Override
    public boolean hasEarningsOn(Long workerId, LocalDate date) {
        return runRepository.existsFinalizedCovering(workerId, date);
    }
}
