package com.stokr.risk.rules;

import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Cluster Detection Rule: Prevents simultaneous batch entries from dominating risk.
 *
 * Detects when 3+ entry orders exist within a 2-minute window (per user).
 * If cluster detected, rejects new entry to prevent correlated batch losses.
 *
 * Background:
 * - 2026-06-08: 04:58:03 cluster (4 simultaneous entries, all lost -7.82%)
 * - Batch processing evaluates 50+ symbols in one market snapshot
 * - Need cluster control to limit correlated risk exposure
 */
@Component
@Order(41)
@RequiredArgsConstructor
@Slf4j
public class ClusterDetectionRule implements RiskRule {

    private final OmsOrderRepository omsOrderRepository;

    @Value("${stokr.risk.cluster-detection-enabled:true}")
    private boolean clusterDetectionEnabled;

    @Value("${stokr.risk.cluster-max-entries:2}")
    private int maxEntriesInWindow;

    @Value("${stokr.risk.cluster-detection-window-minutes:2}")
    private int detectionWindowMinutes;

    @Override
    public String code() {
        return "CLUSTER_DETECTION";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (!clusterDetectionEnabled) {
            return RiskDecision.ok();
        }

        // Only apply to entry orders (BUY/LONG)
        if (context.order().getBacktestRunId() != null) {
            return RiskDecision.ok();  // Skip for backtest
        }

        String side = context.order().getSide();
        if (side == null || !side.toUpperCase().matches("BUY|LONG")) {
            return RiskDecision.ok();  // Only restrict entries, not exits
        }

        // Count orders created in the past N minutes
        Instant windowStart = Instant.now().minus(Duration.ofMinutes(detectionWindowMinutes));
        List<Instant> recentOrderTimes = omsOrderRepository.findRecentOrderTimesForUser(
                context.userId(),
                windowStart,
                Instant.now()
        );

        // Query already filters by time window, so count all returned entries
        int entryCount = recentOrderTimes.size();

        // Reject if count exceeds maximum (allow up to maxEntriesInWindow, reject on maxEntriesInWindow+1)
        if (entryCount > maxEntriesInWindow) {
            log.warn("cluster.detection.triggered user={} entryCount={} windowMinutes={} maxAllowed={}",
                    context.userId(), entryCount, detectionWindowMinutes, maxEntriesInWindow);
            return RiskDecision.reject(
                    code(),
                    String.format(
                            "Cluster detected: %d entries in %d min (max %d allowed). Pause to avoid batch correlation.",
                            entryCount, detectionWindowMinutes, maxEntriesInWindow
                    )
            );
        }

        return RiskDecision.ok();
    }
}
