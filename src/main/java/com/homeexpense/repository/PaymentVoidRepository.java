package com.homeexpense.repository;

import java.util.Optional;

import com.homeexpense.model.finance.PaymentVoid;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentVoidRepository extends JpaRepository<PaymentVoid, Long> {

    Optional<PaymentVoid> findByPaymentId(Long paymentId);

}
