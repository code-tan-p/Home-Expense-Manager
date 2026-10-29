package com.homeexpense.service;

import java.time.Clock;
import java.util.List;

import com.homeexpense.model.AuditEvent;
import com.homeexpense.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final Clock clock;

    public AuditService(AuditEventRepository auditEventRepository, Clock clock) {
        this.auditEventRepository = auditEventRepository;
        this.clock = clock;
    }

    public AuditEvent record(Long workerId, String entityType, Long entityId, String action, String details) {
        return auditEventRepository.save(new AuditEvent(clock.instant(), workerId, entityType, entityId, action, details));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> historyForWorker(Long workerId) {
        return auditEventRepository.findByWorkerIdOrderByIdAsc(workerId);
    }
}
