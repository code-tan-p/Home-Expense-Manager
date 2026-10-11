package com.homeexpense.repository;

import java.time.LocalDate;
import java.util.List;

import com.homeexpense.model.finance.FinancialAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FinancialAdjustmentRepository extends JpaRepository<FinancialAdjustment, Long> {

    @Query("select f from FinancialAdjustment f where f.worker.id = :workerId and f.targetPeriodStart = :month order by f.id")
    List<FinancialAdjustment> findTargeting(Long workerId, LocalDate month);

    @Query("select f from FinancialAdjustment f where f.sourceSettlement.id = :settlementId order by f.id")
    List<FinancialAdjustment> findBySource(Long settlementId);

    @Query("select count(f) > 0 from FinancialAdjustment f where f.reverses.id = :adjustmentId")
    boolean isReversed(Long adjustmentId);

}
