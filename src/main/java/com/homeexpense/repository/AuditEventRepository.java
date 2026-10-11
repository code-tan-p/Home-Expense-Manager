package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByWorkerIdOrderByIdAsc(Long workerId);

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByIdAsc(String entityType, Long entityId);
}
