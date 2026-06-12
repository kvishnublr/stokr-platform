package com.stokr.execution.orphan.service;

import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.execution.orphan.domain.DetectedOrphanPosition;
import com.stokr.execution.orphan.repository.DetectedOrphanPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanPositionDetectionService {

    private final BrokerPositionTruthService brokerPositionTruthService;
    private final OmsOrderRepository omsOrderRepository;
    private final DetectedOrphanPositionRepository orphanRepository;
    private final AuditLogService auditLogService;

    public List<DetectedOrphanPosition> scanForOrphans(UUID userId) {
        try {
            // Placeholder: Uses BrokerPositionTruthService.snapshot() to get broker positions
            // Compares with OMS orders to identify orphans
            // Returns list of DetectedOrphanPosition entities without matching OMS orders
            log.debug("orphan.detection.scan user_id={}", userId);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("orphan.detection.failed user_id={}", userId, e);
            return Collections.emptyList();
        }
    }

    public List<DetectedOrphanPosition> scanAllOrphans() {
        log.info("orphan.scan_all.started");
        List<DetectedOrphanPosition> allOrphans = new ArrayList<>();

        try {
            // Placeholder: Scans all active users for orphaned positions
            log.info("orphan.scan_all.completed count={}", allOrphans.size());
        } catch (Exception e) {
            log.error("orphan.scan_all.failed", e);
        }

        return allOrphans;
    }

    public void recordOrphanDetection(DetectedOrphanPosition orphan) {
        orphanRepository.save(orphan);
        auditLogService.logOrphanDetected(orphan);
    }
}
