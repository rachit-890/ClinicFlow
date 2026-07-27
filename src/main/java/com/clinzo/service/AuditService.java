package com.clinzo.service;

import com.clinzo.domain.AuditLog;
import com.clinzo.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void log(String entityType, Long entityId, String action, String actorId, String oldState, String newState) {
        AuditLog logEntry = AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .actorId(actorId)
                .oldState(oldState)
                .newState(newState)
                .build();
        auditLogRepository.save(logEntry);
        log.debug("AuditLog saved for {} {} - Action: {}", entityType, entityId, action);
    }
}
