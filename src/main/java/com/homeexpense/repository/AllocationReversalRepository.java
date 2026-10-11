package com.homeexpense.repository;

import com.homeexpense.model.finance.AllocationReversal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AllocationReversalRepository extends JpaRepository<AllocationReversal, Long> {
}
