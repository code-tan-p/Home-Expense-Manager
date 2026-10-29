package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.PayArrangement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayArrangementRepository extends JpaRepository<PayArrangement, Long> {

    List<PayArrangement> findByWorkerIdOrderByEffectiveFromAsc(Long workerId);
}
