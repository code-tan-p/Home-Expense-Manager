package com.homeexpense.repository;

import java.time.LocalDate;
import java.util.Optional;

import com.homeexpense.model.earnings.UnrecordedConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UnrecordedConfirmationRepository extends JpaRepository<UnrecordedConfirmation, Long> {

    @Query("""
            select c from UnrecordedConfirmation c
            where c.worker.id = :workerId and c.periodStart = :periodStart and c.supersededAt is null
            """)
    Optional<UnrecordedConfirmation> findCurrent(Long workerId, LocalDate periodStart);
}
