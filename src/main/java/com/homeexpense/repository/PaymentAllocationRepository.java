package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.finance.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    @Query("select a from PaymentAllocation a where a.payment.id = :paymentId and not exists (select x from AllocationReversal x where x.allocation = a) order by a.id")
    List<PaymentAllocation> findActiveByPayment(Long paymentId);

    @Query("select a from PaymentAllocation a where a.settlement.id = :settlementId and not exists (select x from AllocationReversal x where x.allocation = a) order by a.id")
    List<PaymentAllocation> findActiveBySettlement(Long settlementId);

    List<PaymentAllocation> findByPaymentIdOrderByIdAsc(Long paymentId);

    @Query("select a from PaymentAllocation a where a.advance.id = :advanceId and not exists (select x from AllocationReversal x where x.allocation = a) order by a.id")
    List<PaymentAllocation> findActiveByAdvance(Long advanceId);

}
