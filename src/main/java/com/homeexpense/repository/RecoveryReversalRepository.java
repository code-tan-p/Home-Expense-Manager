package com.homeexpense.repository;

import com.homeexpense.model.finance.RecoveryReversal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryReversalRepository extends JpaRepository<RecoveryReversal, Long> {
}
