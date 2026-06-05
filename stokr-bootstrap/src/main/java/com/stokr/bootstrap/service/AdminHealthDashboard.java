package com.stokr.bootstrap.service;

import com.stokr.bootstrap.domain.entity.*;
import com.stokr.bootstrap.repository.*;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminHealthDashboard {

    private final RedisHealthLogRepository redisHealthLog;
    private final MarketDataStalenessLogRepository stalenessLog;
    private final StrategyDriftDetectionLogRepository driftLog;
    private final PositionOrphanDetectionLogRepository orphanLog;

    public SystemHealthSnapshot getCurrentHealth() {
        return SystemHealthSnapshot.builder()
            .timestamp(LocalDateTime.now())
            .redisHealth(getRedisHealth())
            .marketDataHealth(getMarketDataHealth())
            .strategyHealth(getStrategyHealth())
            .positionHealth(getPositionHealth())
            .overallStatus(calculateOverallStatus())
            .build();
    }

    public List<IssueTimeline> getIssueTimeline(int lastHours) {
        LocalDateTime since = LocalDateTime.now().minus(lastHours, ChronoUnit.HOURS);
        List<IssueTimeline> timeline = new ArrayList<>();

        // Redis issues
        var redisIssues = redisHealthLog.findIssuesSince(since);
        for (var log : redisIssues) {
            timeline.add(IssueTimeline.builder()
                .timestamp(log.getCheckTime())
                .severity(log.getIsHealthy() ? "INFO" : "CRITICAL")
                .category("REDIS")
                .description("Redis " + log.getConnectionFactoryState() + " - " + log.getIssuesJson())
                .hasAutoFix(log.getAutoRecoveryAttempted())
                .autoFixSuccessful(log.getRecoverySuccessful())
                .build());
        }

        // Market data issues
        var marketIssues = stalenessLog.findByCheckTimeAfter(since);
        Map<String, LocalDateTime> lastStalePerSymbol = new HashMap<>();
        for (var log : marketIssues) {
            if (log.getIsStale()) {
                lastStalePerSymbol.put(log.getSymbol(), log.getCheckTime());
            }
        }
        for (var entry : lastStalePerSymbol.entrySet()) {
            timeline.add(IssueTimeline.builder()
                .timestamp(entry.getValue())
                .severity("HIGH")
                .category("MARKET_DATA")
                .description(entry.getKey() + " feed stale (> 30 seconds)")
                .hasAutoFix(true)
                .autoFixSuccessful(false)  // Feed recovery attempted
                .build());
        }

        // Drift issues
        var driftIssues = driftLog.findHighSeverityDrifts();
        for (var drift : driftIssues) {
            if (drift.getCheckTime().isAfter(since)) {
                timeline.add(IssueTimeline.builder()
                    .timestamp(drift.getCheckTime())
                    .severity("HIGH")
                    .category("STRATEGY_DRIFT")
                    .description(drift.getStrategyName() + " - " + drift.getPositionDelta() + " position delta")
                    .hasAutoFix(drift.getStrategyPaused())
                    .autoFixSuccessful(true)
                    .build());
            }
        }

        // Orphan issues
        var orphans = orphanLog.findAllOrphans();
        Map<UUID, Integer> orphanCountByUser = new HashMap<>();
        LocalDateTime latestOrphan = null;
        for (var orphan : orphans) {
            if (orphan.getCheckTime().isAfter(since)) {
                orphanCountByUser.merge(orphan.getUserId(), 1, Integer::sum);
                if (latestOrphan == null || orphan.getCheckTime().isAfter(latestOrphan)) {
                    latestOrphan = orphan.getCheckTime();
                }
            }
        }
        if (latestOrphan != null) {
            for (var entry : orphanCountByUser.entrySet()) {
                timeline.add(IssueTimeline.builder()
                    .timestamp(latestOrphan)
                    .severity("HIGH")
                    .category("POSITION_ORPHAN")
                    .description(entry.getValue() + " orphan positions detected")
                    .hasAutoFix(true)
                    .autoFixSuccessful(true)
                    .build());
            }
        }

        return timeline.stream()
            .sorted(Comparator.comparing(IssueTimeline::getTimestamp).reversed())
            .collect(Collectors.toList());
    }

    public SystemDiagnosis diagnoseIssue(String issueType, LocalDateTime when) {
        List<String> findings = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        if ("REDIS".equals(issueType)) {
            var redisIssues = redisHealthLog.findRecentIssues();
            for (var log : redisIssues) {
                if (log.getCheckTime().isBefore(when.plusMinutes(5)) &&
                    log.getCheckTime().isAfter(when.minusMinutes(5))) {
                    findings.add("Redis connection factory state: " + log.getConnectionFactoryState());
                    findings.add("Issues: " + log.getIssuesJson());
                    findings.add("Auto-recovery attempted: " + log.getAutoRecoveryAttempted());
                    if (!log.getRecoverySuccessful()) {
                        recommendations.add("Check Redis server status and logs");
                        recommendations.add("Verify network connectivity to Redis");
                        recommendations.add("Restart Redis if needed");
                    }
                }
            }
        } else if ("MARKET_DATA".equals(issueType)) {
            var staleIssues = stalenessLog.findByIsStaleTrue();
            for (var log : staleIssues) {
                if (log.getCheckTime().isBefore(when.plusMinutes(5)) &&
                    log.getCheckTime().isAfter(when.minusMinutes(5))) {
                    findings.add("Feed: " + log.getFeedSource() + " | Symbol: " + log.getSymbol());
                    findings.add("Last update: " + log.getLastPriceAgeSeconds() + " seconds ago");
                    recommendations.add("Check market data provider connectivity");
                    recommendations.add("Verify API rate limits not exceeded");
                    recommendations.add("Check if market hours are active");
                }
            }
        } else if ("STRATEGY_DRIFT".equals(issueType)) {
            var drifts = driftLog.findAllDrifts();
            for (var drift : drifts) {
                if (drift.getCheckTime().isBefore(when.plusMinutes(5)) &&
                    drift.getCheckTime().isAfter(when.minusMinutes(5))) {
                    findings.add("Strategy: " + drift.getStrategyName());
                    findings.add("Entry drift: " + drift.getEntryDriftDetected());
                    findings.add("Exit drift: " + drift.getExitDriftDetected());
                    findings.add("Expected positions: " + drift.getExpectedOpenPositions());
                    findings.add("Actual positions: " + drift.getActualOpenPositions());
                    findings.add("Severity: " + drift.getDriftSeverity());
                    recommendations.add("Review strategy entry/exit conditions");
                    recommendations.add("Check market conditions against strategy rules");
                    recommendations.add("Verify broker position reconciliation");
                }
            }
        }

        return SystemDiagnosis.builder()
            .issueType(issueType)
            .when(when)
            .findings(findings)
            .recommendations(recommendations)
            .severity(findingsSeverity(findings))
            .build();
    }

    public Map<String, HealthStatus> getComponentStatus() {
        Map<String, HealthStatus> status = new LinkedHashMap<>();

        // Redis health
        var latestRedisLog = redisHealthLog.findMostRecent();
        status.put("Redis", HealthStatus.builder()
            .component("Redis Connection Pool")
            .status(latestRedisLog != null && latestRedisLog.getIsHealthy() ? "HEALTHY" : "CRITICAL")
            .lastCheck(latestRedisLog != null ? latestRedisLog.getCheckTime() : null)
            .details(latestRedisLog != null ? latestRedisLog.getConnectionFactoryState() : "Unknown")
            .build());

        // Market data health
        var staleCount = stalenessLog.findByIsStaleTrue().size();
        status.put("Market Data", HealthStatus.builder()
            .component("Market Data Feeds")
            .status(staleCount == 0 ? "HEALTHY" : "DEGRADED")
            .lastCheck(LocalDateTime.now())
            .details(staleCount + " stale feeds")
            .build());

        // Strategy health
        var highDrifts = driftLog.findHighSeverityDrifts().size();
        status.put("Strategies", HealthStatus.builder()
            .component("Strategy Drift Monitor")
            .status(highDrifts == 0 ? "HEALTHY" : "WARNING")
            .lastCheck(LocalDateTime.now())
            .details(highDrifts + " high-drift strategies")
            .build());

        // Position health
        var orphanCount = orphanLog.findUnresolvedOrphans().size();
        status.put("Positions", HealthStatus.builder()
            .component("Position Tracking")
            .status(orphanCount == 0 ? "HEALTHY" : "WARNING")
            .lastCheck(LocalDateTime.now())
            .details(orphanCount + " unresolved orphans")
            .build());

        return status;
    }

    public IssueAnalysis analyzeRootCause(LocalDateTime startTime, LocalDateTime endTime) {
        List<String> rootCauses = new ArrayList<>();
        List<String> impactChain = new ArrayList<>();
        List<String> preventionMeasures = new ArrayList<>();

        // Check Redis failures in timeframe
        var redisIssues = redisHealthLog.findRecentIssues();
        boolean redisFailure = redisIssues.stream()
            .anyMatch(log -> log.getCheckTime().isAfter(startTime) && log.getCheckTime().isBefore(endTime));

        if (redisFailure) {
            rootCauses.add("Redis Connection Factory STOPPED");
            impactChain.add("1. Redis unavailable → Market data caching fails");
            impactChain.add("2. No market data → Position tracking lost");
            impactChain.add("3. No position data → Reconciliation triggers");
            impactChain.add("4. Reconciliation fails → Orphan positions created");
            preventionMeasures.add("Enable Redis sentinel for high availability");
            preventionMeasures.add("Configure connection pool recovery timeout");
            preventionMeasures.add("Alert on LettuceConnectionFactory state changes");
        }

        // Check market data staleness
        var staleIssues = stalenessLog.findByCheckTimeAfter(startTime);
        boolean marketDataStale = staleIssues.stream()
            .anyMatch(log -> log.getIsStale() && log.getCheckTime().isBefore(endTime));

        if (marketDataStale) {
            rootCauses.add("Market Data Feed Staleness (> 30 seconds)");
            impactChain.add("1. Feed stops updating → Strategy blind");
            impactChain.add("2. No price updates → Entry/exit signals blocked");
            impactChain.add("3. Positions not closed → Risk exposure increases");
            preventionMeasures.add("Monitor feed timestamps every 10 seconds");
            preventionMeasures.add("Auto-reconnect on 30-second staleness");
            preventionMeasures.add("Fallback to backup feed source");
        }

        // Check orphan positions
        var orphans = orphanLog.findAllOrphans();
        boolean hasOrphans = orphans.stream()
            .anyMatch(log -> log.getCheckTime().isAfter(startTime) && log.getCheckTime().isBefore(endTime));

        if (hasOrphans) {
            rootCauses.add("Position Orphan Detection");
            impactChain.add("1. Position missing entry signal/order");
            impactChain.add("2. Broker has position, OMS doesn't (GHOST)");
            impactChain.add("3. Or OMS has position, broker closed (ORPHAN)");
            preventionMeasures.add("Enforce signal_id linkage for all LIVE orders");
            preventionMeasures.add("Validate position ownership on every trade");
            preventionMeasures.add("Reconcile broker positions hourly");
        }

        return IssueAnalysis.builder()
            .startTime(startTime)
            .endTime(endTime)
            .rootCauses(rootCauses)
            .impactChain(impactChain)
            .preventionMeasures(preventionMeasures)
            .build();
    }

    // ============ HELPER METHODS ============

    private ComponentHealth getRedisHealth() {
        var latest = redisHealthLog.findMostRecent();
        if (latest == null) {
            return ComponentHealth.builder()
                .component("Redis")
                .status("UNKNOWN")
                .build();
        }
        return ComponentHealth.builder()
            .component("Redis")
            .status(latest.getIsHealthy() ? "HEALTHY" : "CRITICAL")
            .lastCheck(latest.getCheckTime())
            .metrics(Map.of(
                "Factory State", latest.getConnectionFactoryState() != null ? latest.getConnectionFactoryState() : "Unknown",
                "Connections Active", String.valueOf(latest.getCurrentConnections()),
                "Cache Hit Rate", String.valueOf(latest.getMissRatePercent()) + "%"
            ))
            .build();
    }

    private ComponentHealth getMarketDataHealth() {
        var staleCount = stalenessLog.findByIsStaleTrue().size();
        return ComponentHealth.builder()
            .component("Market Data")
            .status(staleCount == 0 ? "HEALTHY" : "DEGRADED")
            .metrics(Map.of(
                "Stale Feeds", String.valueOf(staleCount),
                "Last Check", LocalDateTime.now().toString()
            ))
            .build();
    }

    private ComponentHealth getStrategyHealth() {
        var highDrifts = driftLog.findHighSeverityDrifts();
        return ComponentHealth.builder()
            .component("Strategies")
            .status(highDrifts.isEmpty() ? "HEALTHY" : "WARNING")
            .metrics(Map.of(
                "High Drift Count", String.valueOf(highDrifts.size()),
                "Paused Strategies", String.valueOf(highDrifts.stream()
                    .filter(d -> d.getStrategyPaused() != null && d.getStrategyPaused()).count())
            ))
            .build();
    }

    private ComponentHealth getPositionHealth() {
        var unresolved = orphanLog.findUnresolvedOrphans();
        return ComponentHealth.builder()
            .component("Positions")
            .status(unresolved.isEmpty() ? "HEALTHY" : "WARNING")
            .metrics(Map.of(
                "Unresolved Orphans", String.valueOf(unresolved.size())
            ))
            .build();
    }

    private String calculateOverallStatus() {
        var redisOk = redisHealthLog.findMostRecent() != null &&
                     redisHealthLog.findMostRecent().getIsHealthy();
        var noStaleFeeds = stalenessLog.findByIsStaleTrue().isEmpty();
        var noHighDrifts = driftLog.findHighSeverityDrifts().isEmpty();
        var noOrphans = orphanLog.findUnresolvedOrphans().isEmpty();

        if (redisOk && noStaleFeeds && noHighDrifts && noOrphans) {
            return "HEALTHY";
        } else if (!redisOk) {
            return "CRITICAL";
        } else if (!noStaleFeeds) {
            return "DEGRADED";
        } else {
            return "WARNING";
        }
    }

    private String findingsSeverity(List<String> findings) {
        if (findings.isEmpty()) return "NONE";
        if (findings.size() >= 5) return "CRITICAL";
        if (findings.size() >= 3) return "HIGH";
        return "MEDIUM";
    }

    // ============ DTO CLASSES ============

    @Data
    @Builder
    public static class SystemHealthSnapshot {
        private LocalDateTime timestamp;
        private ComponentHealth redisHealth;
        private ComponentHealth marketDataHealth;
        private ComponentHealth strategyHealth;
        private ComponentHealth positionHealth;
        private String overallStatus;
    }

    @Data
    @Builder
    public static class ComponentHealth {
        private String component;
        private String status;
        private LocalDateTime lastCheck;
        private Map<String, String> metrics;
    }

    @Data
    @Builder
    public static class IssueTimeline {
        private LocalDateTime timestamp;
        private String severity;
        private String category;
        private String description;
        private Boolean hasAutoFix;
        private Boolean autoFixSuccessful;
    }

    @Data
    @Builder
    public static class HealthStatus {
        private String component;
        private String status;
        private LocalDateTime lastCheck;
        private String details;
    }

    @Data
    @Builder
    public static class SystemDiagnosis {
        private String issueType;
        private LocalDateTime when;
        private List<String> findings;
        private List<String> recommendations;
        private String severity;
    }

    @Data
    @Builder
    public static class IssueAnalysis {
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private List<String> rootCauses;
        private List<String> impactChain;
        private List<String> preventionMeasures;
    }
}
