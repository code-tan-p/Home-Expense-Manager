package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.PayRate;
import com.homeexpense.model.RateType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayRateRepository extends JpaRepository<PayRate, Long> {

    List<PayRate> findByWorkerIdOrderByEffectiveFromAsc(Long workerId);

    List<PayRate> findByWorkerIdAndRateTypeOrderByEffectiveFromAsc(Long workerId, RateType rateType);
}
