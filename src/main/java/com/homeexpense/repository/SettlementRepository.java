package com.homeexpense.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.homeexpense.model.finance.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByWorkerIdAndPeriodStart(Long workerId, LocalDate periodStart);

    List<Settlement> findByWorkerIdOrderByPeriodStartAsc(Long workerId);

}
