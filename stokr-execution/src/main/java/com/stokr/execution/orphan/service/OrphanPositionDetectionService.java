package com.stokr.execution.orphan.service;

import com.stokr.execution.broker.service.BrokerPositionTruthService;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.execution.orphan.domain.DetectedOrphanPosition;
import com.stokr.execution.orphan.repository.DetectedOrphanPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
            var brokerPositions = brokerPositionTruthService.getBrokerPositions(userId);
            var activeOrders = omsOrderRepository.findActiveOrdersForUser(userId);

            Set<String> omsSymbols = activeOrders.stream()
                    .map(order -> order.getSymbol())
                    .collect(Collectors.toSet());

            List<DetectedOrphanPosition> orphans = new ArrayList<>();

            for (var brokerPos : brokerPositions) {
                if (brokerPos.getQuantity().signum() > 0 && !omsSymbols.contains(brokerPos.getSymbol())) {
                    DetectedOrphanPosition orphan = new DetectedOrphanPosition();
                    orphan.setId(UUID.randomUUID());
                    orphan.setCreatedAt(OffsetDateTime.now());
                    orphan.setUpdatedAt(OffsetDateTime.now());
                    orphan.setVersion(0L);
                    orphan.setUserId(userId);
                    orphan.setSymbol(brokerPos.getSymbol());
                    orphan.setQuantity(brokerPos.getQuantity());
                    orphan.setBrokerEntryTime(brokerPos.getEntryTime());
                    orphan.setBrokerEntryPrice(brokerPos.getEntryPrice());
                    orphan.setCurrentValue(brokerPos.getQuantity().multiply(brokerPos.getCurrentPrice()));
                    orphan.setDetectionTimestamp(OffsetDateTime.now());
                    orphan.setDetectionSource("SCHEDULER");
                    orphan.setStatus("DETECTED");

                    orphans.add(orphan);
                }
            }

            if (!orphans.isEmpty()) {
                orphanRepository.saveAll(orphans);
                orphans.forEach(auditLogService::logOrphanDetected);
                log.info("orphan.detection.completed user_id={} count={}", userId, orphans.size());
            }

            return orphans;
        } catch (Exception e) {
            log.error("orphan.detection.failed user_id={}", userId, e);
            return Collections.emptyList();
        }
    }

    public List<DetectedOrphanPosition> scanAllOrphans() {
        log.info("orphan.scan_all.started");
        List<DetectedOrphanPosition> allOrphans = new ArrayList<>();

        try {
            var allUsers = brokerPositionTruthService.getAllActiveUsers();
            for (UUID userId : allUsers) {
                allOrphans.addAll(scanForOrphans(userId));
            }
        } catch (Exception e) {
            log.error("orphan.scan_all.failed", e);
        }

        log.info("orphan.scan_all.completed count={}", allOrphans.size());
        return allOrphans;
    }

    public void recordOrphanDetection(DetectedOrphanPosition orphan) {
        orphanRepository.save(orphan);
        auditLogService.logOrphanDetected(orphan);
    }
}
