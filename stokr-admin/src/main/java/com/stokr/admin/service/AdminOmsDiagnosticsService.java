package com.stokr.admin.service;

import com.stokr.execution.safety.*;
import com.stokr.oms.service.PortfolioQueryService;
import com.stokr.strategy.operational.StrategyExecutionModeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminOmsDiagnosticsService {

    private final TradingKillSwitchService killSwitchService;
    private final BrokerDisconnectProtectionService brokerDisconnectProtectionService;
    private final OmsExposureControlService exposureControlService;
    private final OmsExecutionDedupeService dedupeService;
    private final BrokerExecutionTelemetryService brokerExecutionTelemetryService;
    private final OmsSafetyBlockedOrderRepository blockedOrderRepository;
    private final StrategyExecutionModeService executionModeService;
    private final PortfolioQueryService portfolioQueryService;
    private final MarketCloseProtectionService marketCloseProtectionService;

    public Map<String, Object> diagnostics(UUID userId) {
        Instant now = Instant.now();
        Instant since = now.minus(24, ChronoUnit.HOURS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectedAt", now.toString());
        out.put("killSwitch", killSwitchService.statusMap(0));
        out.put("brokerConnection", brokerDisconnectProtectionService.snapshot(userId));
        out.put("activeLimits", exposureControlService.activeLimitsSnapshot());
        out.put("strategyExecutionModes", executionModeService.allModes());
        out.put("marketCloseProtection", Map.of(
                "noNewEntriesAfter", marketCloseProtectionService.noNewEntriesAfter().toString(),
                "flattenTime", marketCloseProtectionService.flattenTime().toString(),
                "blocksNewLiveEntriesNow", marketCloseProtectionService.blocksNewLiveEntries(now)
        ));
        if (userId != null) {
            var overview = portfolioQueryService.overview(userId);
            out.put("dailyPnl", Map.of(
                    "todayMtm", overview.todayMtm(),
                    "openPositionCount", overview.openPositionCount()
            ));
        }
        out.put("blockedOrdersLast24h", blockedOrderRepository.countByCreatedAtAfter(since));
        out.put("duplicatePrevention", Map.of(
                "dedupeWindowSeconds", dedupeService.dedupeWindowSeconds(),
                "activeKeysTracked", dedupeService.activeKeyCount()
        ));
        out.put("executionLatency", Map.of(
                "avgAckLatencyMsLast24h", brokerExecutionTelemetryService.avgAckLatencyMsSince(since),
                "telemetryEventsLast24h", brokerExecutionTelemetryService.telemetryCountSince(since)
        ));
        return out;
    }
}
