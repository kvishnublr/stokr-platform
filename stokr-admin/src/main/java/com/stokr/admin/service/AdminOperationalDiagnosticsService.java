package com.stokr.admin.service;

import com.stokr.common.market.NseMarketSession;
import com.stokr.marketdata.integrity.MarketDataIntegrityService;
import com.stokr.marketdata.monitor.FeedHealthMonitorService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.integrity.StrategyIntegrityProfile;
import com.stokr.strategy.operational.StrategyExecutionMode;
import com.stokr.strategy.operational.StrategyExecutionModeService;
import com.stokr.strategy.operational.StrategyRuntimeHealth;
import com.stokr.strategy.operational.StrategyRuntimeHealthService;
import com.stokr.strategy.operational.TradingSafeStartupGateService;
import com.stokr.strategy.dto.StrategyCatalogSignalStatsDto;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.risk.service.StrategyToggleService;
import com.stokr.strategy.service.StrategyCatalogSignalStatsService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminOperationalDiagnosticsService {

    private final FeedHealthMonitorService feedHealthMonitorService;
    private final MarketDataIntegrityService marketDataIntegrityService;
    private final StrategyGeneratorIntegrityGate strategyIntegrityGate;
    private final StrategyExecutionModeService executionModeService;
    private final StrategyRuntimeHealthService runtimeHealthService;
    private final TradingSafeStartupGateService safeStartupGateService;
    private final StrategySignalRepository signalRepository;
    private final StrategyCatalogSignalStatsService catalogSignalStatsService;
    private final StrategyToggleService strategyToggleService;
    private final EntityManager entityManager;

    public Map<String, Object> liveDiagnostics(Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectedAt", now.toString());
        out.put("feedHealth", feedHealthMonitorService.snapshotMap(now));
        out.put("marketDataIntegrity", marketDataIntegrityService.diagnosticsSnapshot(now));
        out.put("safeStartup", safeStartupGateService.snapshot(now));
        out.put("strategyModes", executionModeService.allModes());

        List<StrategyRuntimeHealth> health = runtimeHealthService.healthForToday(now);
        out.put("strategyRuntimeHealth", runtimeHealthRows(health));
        out.put("marketSessionOpen", NseMarketSession.isRegularSessionOpen(now));
        out.put("blockedStrategies", blockedStrategies(health, now));
        out.put("integrityFailuresToday", integrityFailureCount(now));
        out.put("activeTrades", activeTradeCount());
        out.put("staleSymbols", staleSymbolSample(now));
        out.put("redisStrategyToggleWarnings", redisStrategyToggleWarnings());
        out.put("signalPipelineAdminActions", signalPipelineAdminActions());
        return out;
    }

    /**
     * LIVE-validated strategies with Redis toggle off block OMS at risk ({@code STRATEGY_DISABLED}).
     */
    private List<Map<String, Object>> redisStrategyToggleWarnings() {
        List<Map<String, Object>> warnings = new ArrayList<>();
        Set<String> keys = executionModeService.liveValidatedStrategyKeys();
        for (String strategyKey : keys) {
            if (strategyToggleService.isEnabled(strategyKey)) {
                continue;
            }
            Boolean redisOverride = strategyToggleService.redisOverride(strategyKey);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategyKey", strategyKey);
            row.put("configuredMode", executionModeService.modeFor(strategyKey).name());
            row.put("redisOverride", redisOverride != null ? redisOverride : "default");
            row.put("impact", "Orders rejected at risk: Strategy disabled");
            row.put("enablePath", "/api/admin/strategy/toggle?strategyKey="
                    + strategyKey.toUpperCase(Locale.ROOT) + "&enabled=true");
            warnings.add(row);
        }
        return warnings;
    }

    private Map<String, Object> signalPipelineAdminActions() {
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("regenerateCatalogSignal", action(
                "POST",
                "/api/admin/feed/regenerate-catalog-signal",
                "preferLive=true&signalId=<optional-uuid>",
                "Clone last production signal (or by id) and dispatch OMS ??? not Test Signal Lab"));
        actions.put("redispatchOrphanSignals", action(
                "POST",
                "/api/admin/feed/redispatch-orphan-signals",
                null,
                "Re-run OMS for today's signals that have no order"));
        actions.put("niftyGapFill", action(
                "POST",
                "/api/admin/feed/nifty-gap-fill",
                null,
                "Backfill NIFTY index candles for integrity gate"));
        actions.put("strategyRedisToggle", action(
                "POST",
                "/api/admin/strategy/toggle",
                "strategyKey=ADV_CASH&enabled=true",
                "Enable/disable strategy at risk (Redis)"));
        actions.put("adminHealth", action(
                "GET",
                "/api/admin/health",
                null,
                "Kill switch, live armed, queue names"));
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("safetyDiagnostics", "/admin/safety-diagnostics");
        ui.put("signalMonitor", "/admin/signals");
        ui.put("omsMonitor", "/admin/oms");
        ui.put("testSignalLab", "/admin/test-signal-lab");
        ui.put("commandCenter", "/admin/command-center");
        actions.put("uiPages", ui);
        return actions;
    }

    private static Map<String, Object> action(String method, String path, String query, String description) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("method", method);
        row.put("path", path);
        if (query != null) {
            row.put("query", query);
        }
        row.put("description", description);
        return row;
    }

    private List<Map<String, Object>> runtimeHealthRows(List<StrategyRuntimeHealth> health) {
        Map<String, Long> persistedByStrategy = new LinkedHashMap<>();
        for (StrategyCatalogSignalStatsDto stat : catalogSignalStatsService.signalsTodayByStrategyKey()) {
            persistedByStrategy.put(stat.strategyKey(), stat.signalsToday());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StrategyRuntimeHealth row : health) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategyName", row.getStrategyName());
            m.put("executionMode", row.getExecutionMode());
            m.put("scansAttempted", row.getScansAttempted());
            m.put("scansBlockedIntegrity", row.getScansBlockedIntegrity());
            m.put("scansBlockedFeed", row.getScansBlockedFeed());
            m.put("signalsGenerated", row.getSignalsGenerated());
            m.put("signalsPersistedToday", persistedByStrategy.getOrDefault(row.getStrategyName(), 0L));
            m.put("tradesOpened", row.getTradesOpened());
            m.put("tradesClosed", row.getTradesClosed());
            m.put("rejectionRate", row.getRejectionRate());
            m.put("lastScanTime", row.getLastScanTime());
            m.put("lastSignalTime", row.getLastSignalTime());
            m.put("lastRejectionReason", row.getLastRejectionReason());
            rows.add(m);
        }
        return rows;
    }

    private List<Map<String, Object>> blockedStrategies(List<StrategyRuntimeHealth> health, Instant now) {
        boolean sessionOpen = NseMarketSession.isRegularSessionOpen(now);
        boolean feedHealthy = feedHealthMonitorService.isHealthyForLiveExecution(now);
        List<Map<String, Object>> blocked = new ArrayList<>();
        for (StrategyRuntimeHealth row : health) {
            StrategyExecutionMode mode = StrategyExecutionMode.parse(row.getExecutionMode());
            if (mode == StrategyExecutionMode.DISABLED) {
                blocked.add(Map.of(
                        "strategyName", row.getStrategyName(),
                        "reason", "EXECUTION_MODE_DISABLED"));
            } else if (sessionOpen && isStrategyCurrentlyBlocked(row, now, feedHealthy)) {
                String reason = resolveLiveBlockReason(row, now, feedHealthy);
                blocked.add(Map.of("strategyName", row.getStrategyName(), "reason", reason));
            }
        }
        for (var entry : executionModeService.allModes().entrySet()) {
            if ("DISABLED".equals(entry.getValue())) {
                boolean listed = blocked.stream().anyMatch(b -> entry.getKey().equals(b.get("strategyName")));
                if (!listed) {
                    blocked.add(Map.of("strategyName", entry.getKey(), "reason", "EXECUTION_MODE_DISABLED"));
                }
            }
        }
        return blocked;
    }

    private boolean isStrategyCurrentlyBlocked(
            StrategyRuntimeHealth row, Instant now, boolean feedHealthy) {
        String strategyKey = row.getStrategyName();
        if (StrategyExecutionMode.parse(row.getExecutionMode()) == StrategyExecutionMode.DISABLED) {
            return true;
        }
        if (!feedHealthy) {
            return true;
        }
        if (StrategyIntegrityProfile.forStrategy(strategyKey).requiresNiftyOpeningSession()) {
            return !strategyIntegrityGate.isStrategyScanAllowed(strategyKey, now);
        }
        return row.getLastRejectionReason() != null
                && (row.getScansBlockedIntegrity() > 0 || row.getScansBlockedFeed() > 0);
    }

    private String resolveLiveBlockReason(StrategyRuntimeHealth row, Instant now, boolean feedHealthy) {
        if (!feedHealthy) {
            return row.getLastRejectionReason() != null ? row.getLastRejectionReason() : "FEED_STALE";
        }
        String live = strategyIntegrityGate.scanBlockReason(row.getStrategyName(), now);
        if (live != null && !"OK".equals(live)) {
            return live;
        }
        return row.getLastRejectionReason() != null ? row.getLastRejectionReason() : "BLOCKED";
    }

    private long integrityFailureCount(Instant now) {
        LocalDate session = now.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        Object r = entityManager.createNativeQuery("""
                select count(*) from (
                    select 1 from market_data_integrity_rejections
                    where session_date = :d
                    group by strategy_name, symbol, rejection_reason
                ) deduped
                """)
                .setParameter("d", session)
                .getSingleResult();
        return r instanceof Number n ? n.longValue() : 0L;
    }

    private long activeTradeCount() {
        return signalRepository.findRunningSignalsSince(
                Instant.now().minusSeconds(8 * 3600L),
                PageRequest.of(0, 500)).size();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> staleSymbolSample(Instant now) {
        if (!NseMarketSession.isRegularSessionOpen(now)) {
            return List.of();
        }
        List<Object[]> rows = entityManager.createNativeQuery("""
                select symbol, max(open_time) as mx,
                       extract(epoch from (current_timestamp - max(open_time))) as lag_sec
                from marketdata_candles
                where deleted = false and timeframe = '1m'
                group by symbol
                having extract(epoch from (current_timestamp - max(open_time))) > 120
                order by lag_sec desc
                limit 12
                """).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("symbol", row[0]);
            one.put("latestOpenTime", row[1] != null ? row[1].toString() : null);
            one.put("lagSeconds", row[2] instanceof Number n ? n.longValue() : null);
            out.add(one);
        }
        return out;
    }
}
