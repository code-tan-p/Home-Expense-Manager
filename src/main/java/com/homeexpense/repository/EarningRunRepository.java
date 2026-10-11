package com.homeexpense.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.homeexpense.model.earnings.EarningRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EarningRunRepository extends JpaRepository<EarningRun, Long> {

    /** The active (not superseded) run for a worker and month. There is at most one. */
    @Query("""
            select r from EarningRun r
            where r.worker.id = :workerId and r.periodStart = :periodStart
              and not exists (select s from EarningRunSupersession s where s.supersededRun = r)
            """)
    Optional<EarningRun> findActive(Long workerId, LocalDate periodStart);

    /** Every run for a worker and month, oldest first, including superseded versions. */
    List<EarningRun> findByWorkerIdAndPeriodStartOrderByIdAsc(Long workerId, LocalDate periodStart);

    @Query("""
            select count(r) > 0 from EarningRun r
            where r.worker.id = :workerId and r.status = com.homeexpense.model.earnings.EarningRunStatus.FINALIZED
              and r.periodStart <= :date and r.periodEnd >= :date
              and not exists (select s from EarningRunSupersession s where s.supersededRun = r)
            """)
    boolean existsFinalizedCovering(Long workerId, LocalDate date);

    @Query("""
            select count(r) > 0 from EarningRun r
            where r.worker.id = :workerId and r.status = com.homeexpense.model.earnings.EarningRunStatus.FINALIZED
              and r.periodEnd >= :date
              and not exists (select s from EarningRunSupersession s where s.supersededRun = r)
            """)
    boolean existsFinalizedEndingOnOrAfter(Long workerId, LocalDate date);
}
