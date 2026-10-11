package com.homeexpense.repository;

import java.util.List;
import java.util.Optional;

import com.homeexpense.model.finance.SettlementVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SettlementVersionRepository extends JpaRepository<SettlementVersion, Long> {

    @Query("select v from SettlementVersion v where v.settlement.id = :settlementId and v.status <> com.homeexpense.model.finance.SettlementStatus.SUPERSEDED")
    Optional<SettlementVersion> findCurrent(Long settlementId);

    List<SettlementVersion> findBySettlementIdOrderByVersionNoAsc(Long settlementId);

    @Query("select v from SettlementVersion v where v.settlement.worker.id = :workerId and v.status <> com.homeexpense.model.finance.SettlementStatus.SUPERSEDED order by v.settlement.periodStart")
    List<SettlementVersion> findCurrentForWorker(Long workerId);

}
