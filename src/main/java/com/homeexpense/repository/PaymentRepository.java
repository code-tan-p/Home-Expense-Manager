package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.finance.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByWorkerIdOrderByPaidOnAscIdAsc(Long workerId);

}
