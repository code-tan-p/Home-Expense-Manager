package com.homeexpense.repository;

import java.time.LocalDate;
import java.util.List;

import com.homeexpense.model.EmploymentPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmploymentPeriodRepository extends JpaRepository<EmploymentPeriod, Long> {

    List<EmploymentPeriod> findByWorkerIdOrderByStartDateAsc(Long workerId);

    @Query("""
            select p from EmploymentPeriod p
            where p.worker.id = :workerId
              and p.startDate <= :date
              and (p.endDate is null or p.endDate >= :date)
            """)
    List<EmploymentPeriod> findCovering(Long workerId, LocalDate date);

    @Query("""
            select distinct p.worker.id from EmploymentPeriod p
            where p.startDate <= :date
              and (p.endDate is null or p.endDate >= :date)
            """)
    List<Long> findWorkerIdsEmployedOn(LocalDate date);
}
