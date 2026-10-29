package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.finance.Advance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdvanceRepository extends JpaRepository<Advance, Long> {

    List<Advance> findByWorkerIdOrderByGivenOnAscIdAsc(Long workerId);

}
