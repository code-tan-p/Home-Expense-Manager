package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.finance.AdvanceRecovery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdvanceRecoveryRepository extends JpaRepository<AdvanceRecovery, Long> {

    @Query("select r from AdvanceRecovery r where r.advance.id = :advanceId and not exists (select x from RecoveryReversal x where x.recovery = r) order by r.id")
    List<AdvanceRecovery> findActiveByAdvance(Long advanceId);

    @Query("select r from AdvanceRecovery r where r.settlement.id = :settlementId and not exists (select x from RecoveryReversal x where x.recovery = r) order by r.id")
    List<AdvanceRecovery> findActiveBySettlement(Long settlementId);

}
