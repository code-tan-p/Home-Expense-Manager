package com.homeexpense.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.homeexpense.model.WorkRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WorkRecordRepository extends JpaRepository<WorkRecord, Long> {

    /** Current (not voided) records for a worker and date. */
    @Query("""
            select r from WorkRecord r
            where r.worker.id = :workerId and r.workDate = :date and r.voidedAt is null
            order by r.id
            """)
    List<WorkRecord> findCurrent(Long workerId, LocalDate date);

    @Query("""
            select r from WorkRecord r
            where r.scheduleEntry.id = :scheduleEntryId and r.workDate = :date and r.voidedAt is null
            """)
    Optional<WorkRecord> findCurrentForExpected(Long scheduleEntryId, LocalDate date);

    /** Current records for a worker between two dates (inclusive), earliest first. */
    @Query("""
            select r from WorkRecord r
            where r.worker.id = :workerId and r.voidedAt is null
              and r.workDate >= :from and r.workDate <= :to
            order by r.workDate, r.id
            """)
    List<WorkRecord> findCurrentBetween(Long workerId, LocalDate from, LocalDate to);

    /** All records, including voided ones, for a worker between two dates (inclusive). */
    @Query("""
            select r from WorkRecord r
            where r.worker.id = :workerId and r.workDate >= :from and r.workDate <= :to
            order by r.workDate, r.id
            """)
    List<WorkRecord> findAllBetween(Long workerId, LocalDate from, LocalDate to);

    Optional<WorkRecord> findByReplacesId(Long replacedRecordId);
}
