package com.homeexpense.repository;

import com.homeexpense.model.earnings.EarningRunSupersession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EarningRunSupersessionRepository extends JpaRepository<EarningRunSupersession, Long> {

    java.util.Optional<EarningRunSupersession> findBySupersededRunId(Long runId);

    @Query("""
            select s from EarningRunSupersession s
            where s.supersededRun.worker.id = :workerId and s.supersededRun.periodStart = :periodStart
              and s.replacementRun is null
            """)
    java.util.Optional<EarningRunSupersession> findAwaitingReplacement(Long workerId, java.time.LocalDate periodStart);

}
