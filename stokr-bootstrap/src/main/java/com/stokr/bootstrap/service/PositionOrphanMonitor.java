package com.stokr.bootstrap.service;

import com.stokr.bootstrap.domain.entity.PositionOrphanDetectionLog;
import com.stokr.bootstrap.repository.PositionOrphanDetectionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@Profile("v2")
@ConditionalOnProperty(name = "stokr.monitoring.position-orphan.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PositionOrphanMonitor {

    private final PositionOrphanDetectionLogRepository orphanLogRepository;

    @Scheduled(fixedRate = 60000)  // Every 60 seconds
    @Transactional
    public void detectOrphanPositions() {
        log.debug("Scanning for orphaned/ghost positions...");

        // TODO: For each position in broker:
        // 1. Check if OMS knows about it (entry_signal_id + entry_order_id)
        // 2. If broker has it but OMS doesn't -> GHOST (broker opened without order)
        // 3. If OMS has it but broker closed it -> ORPHAN (position closed externally)
        // 4. Create synthetic exits for orphans
        // 5. Remove ghosts from broker tracking
    }

    @Transactional
    public void recordOrphanDetection(UUID userId, UUID positionId, String symbol,
                                     boolean brokerHasPosition, boolean omsHasPosition,
                                     boolean noEntrySignal, boolean noEntryOrder) {

        boolean isOrphan = omsHasPosition && !brokerHasPosition;
        boolean isGhost = brokerHasPosition && !omsHasPosition;

        var orphanLog = PositionOrphanDetectionLog.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .positionId(positionId)
            .symbol(symbol)
            .brokerHasPosition(brokerHasPosition)
            .omsHasPosition(omsHasPosition)
            .noEntrySignalFound(noEntrySignal)
            .noEntryOrderFound(noEntryOrder)
            .isOrphan(isOrphan)
            .checkTime(LocalDateTime.now())
            .build();

        orphanLogRepository.save(orphanLog);

        if (isOrphan) {
            log.error("ORPHAN_POSITION_DETECTED: position={} symbol={} (OMS has it, broker closed it)",
                positionId, symbol);
            createSyntheticExit(positionId);
        } else if (isGhost) {
            log.error("GHOST_POSITION_DETECTED: symbol={} (Broker has it, OMS doesn't)",
                symbol);
            removeGhostFromTracking(symbol);
        }
    }

    private void createSyntheticExit(UUID positionId) {
        log.warn("Creating synthetic exit for orphaned position {}", positionId);
        // TODO: Create OmsExecution with is_synthetic=true
        // TODO: Reason = "External broker exit detected"
    }

    private void removeGhostFromTracking(String symbol) {
        log.warn("Removing ghost position from tracking: {}", symbol);
        // TODO: Query broker for exact position
        // TODO: Mark as ghost in OMS
        // TODO: Prevent further orders
    }

    public boolean hasOrphans(UUID userId) {
        // TODO: Query orphan logs for this user in last 5 minutes
        return false;
    }
}
