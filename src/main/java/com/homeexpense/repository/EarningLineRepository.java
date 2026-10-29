package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.earnings.EarningLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EarningLineRepository extends JpaRepository<EarningLine, Long> {

    List<EarningLine> findByRunIdOrderByLineNo(Long runId);

    @Query("""
            select count(l) > 0 from EarningLine l
            where l.payRate.id = :payRateId and l.run.status = com.homeexpense.model.earnings.EarningRunStatus.FINALIZED
              and not exists (select s from EarningRunSupersession s where s.supersededRun = l.run)
            """)
    boolean existsFinalizedUsingRate(Long payRateId);
}
