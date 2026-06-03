package com.stokr.admin.service;

import com.stokr.common.market.NseMarketSession;
import com.stokr.marketdata.monitor.FeedHealthMonitorService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.operational.StrategyExecutionMode;
import com.stokr.strategy.operational.StrategyExecutionModeService;
import com.stokr.strategy.operational.StrategyRuntimeHealth;
import com.stokr.strategy.operational.StrategyRuntimeHealthService;
import com.stokr.strategy.operational.TradingSafeStartupGateService;
import com.stokr.strategy.dto.StrategyCatalogSignalStatsDto;
import com.stokr.strategy.repository.StrategySignalRepository;
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
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminOperationalDiagnosticsService {

    private final FeedHealthMonitorService feedHealthMonitorService;
    private final StrategyExecutionModeService executionModeService;
    private final StrategyRuntimeHealthService runtimeHealthService;
    private final TradingSafeStartupGateService safeStartupGateService;
    private final StrategySignalRepository signalRepository;
    private final StrategyCatalogSignalStatsService catalogSignalStatsService;
    private final EntityManager entityManager;

    public Map<String, Object> liveDiagnostics(Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectedAt", now.toString());
        out.put("feedHealth", feedHealthMonitorService.snapshotMap(now));
        out.put("safeStartup", safeStartupGateService.snapshot(now));
        out.put("strategyModes", executionModeService.allModes());

        List<StrategyRuntimeHealth> health = runtimeHealthService.healthForToday(now);
        out.put("strategyRuntimeHealth", runtimeHealthRows(health));
        out.put("marketSessionOpen", NseMarketSession.isRegularSessionOpen(now));
        out.put("blockedStrategies", blockedStrategies(health, now));
        out.put("integrityFailuresToday", integrityFailureCount(now));
        out.put("activeTrades", activeTradeCount());
        out.put("staleSymbols", staleSymbolSample(now));
        return out;
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
        List<Map<String, Object>> blocked = new ArrayList<>();
        for (StrategyRuntimeHealth row : health) {
            StrategyExecutionMode mode = StrategyExecutionMode.parse(row.getExecutionMode());
            if (mode == StrategyExecutionMode.DISABLED) {
                blocked.add(Map.of(
                        "strategyName", row.getStrategyName(),
                        "reason", "EXECUTION_MODE_DISABLED"));
            } else if (sessionOpen) {
                if (row.getScansBlockedFeed() > 0) {
                    String reason = row.getLastRejectionReason() != null ? row.getLastRejectionReason() : "FEED_STALE";
                    blocked.add(Map.of("strategyName", row.getStrategyName(), "reason", reason));
                } else if (row.getLastRejectionReason() != null && row.getScansBlockedIntegrity() > 0) {
                    blocked.add(Map.of(
                            "strategyName", row.getStrategyName(),
                            "reason", row.getLastRejectionReason()));
                }
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
