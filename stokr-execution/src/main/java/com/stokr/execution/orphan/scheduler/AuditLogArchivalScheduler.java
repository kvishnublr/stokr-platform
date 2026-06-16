package com.stokr.execution.orphan.scheduler;

import com.stokr.execution.orphan.domain.OrphanAuditLog;
import com.stokr.execution.orphan.repository.OrphanAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "stokr.orphan.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class AuditLogArchivalScheduler {

    private final OrphanAuditLogRepository auditLogRepository;

    @Scheduled(cron = "${stokr.orphan.archive.cron:0 0 0 * * SUN}")
    public void archiveOldAuditLogs() {
        log.info("audit_archival.started");

        try {
            Instant retentionThreshold = Instant.now().minusSeconds(730 * 24 * 60 * 60L);
            List<OrphanAuditLog> expiredLogs = auditLogRepository.findExpiredLogs(retentionThreshold);

            int archivedCount = 0;
            for (OrphanAuditLog auditLog : expiredLogs) {
                // In production, export to archive storage first
                auditLogRepository.delete(auditLog);
                archivedCount++;
            }

            log.info("audit_archival.completed archived_count={}", archivedCount);
        } catch (Exception e) {
            log.error("audit_archival.failed", e);
        }
    }
}
