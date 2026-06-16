package com.stokr.strategy.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Automatic live-trading gate driven by rolling entry edge.
 *
 * A strategy whose rolling target-first rate sits below the breakeven rate implied
 * by its own average risk:reward (plus a cost buffer) gets an active demotion row;
 * {@link com.stokr.strategy.operational.StrategyExecutionModeService} downgrades it
 * to PAPER while a demotion is active. The demotion lifts automatically when the
 * rolling edge recovers comfortably above breakeven.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyEdgeGateService {

    private final JdbcTemplate jdbcTemplate;

    /** Sessions included in the rolling edge window. */
    @Value("${stokr.strategy.entry-edge.window-sessions:14}")
    private int windowSessions;

    /** Minimum resolved signals before the gate may act ??? avoids hair-trigger demotions. */
    @Value("${stokr.strategy.entry-edge.min-signals:30}")
    private int minSignals;

    /** Demote when target-first %% is below breakeven + this buffer (covers costs/slippage). */
    @Value("${stokr.strategy.entry-edge.demote-buffer-pct:2.0}")
    private double demoteBufferPct;

    /** Lift the demotion only when edge clears breakeven by this margin. */
    @Value("${stokr.strategy.entry-edge.lift-buffer-pct:8.0}")
    private double liftBufferPct;

    @Value("${stokr.strategy.entry-edge.gate-enabled:true}")
    private boolean gateEnabled;

    private final AtomicReference<CachedDemotions> cache = new AtomicReference<>();

    /** Strategies currently demoted to PAPER by the edge gate (cached ~60s). */
    public Set<String> activeDemotions() {
        CachedDemotions cached = cache.get();
        if (cached != null && cached.fetchedAt().isAfter(Instant.now().minus(Duration.ofSeconds(60)))) {
            return cached.keys();
        }
        try {
            List<String> keys = jdbcTemplate.queryForList(
                    "SELECT strategy_key FROM strategy_edge_demotions WHERE active = true", String.class);
            Set<String> set = keys.stream()
                    .map(k -> k.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            cache.set(new CachedDemotions(set, Instant.now()));
            return set;
        } catch (Exception ex) {
            // Table may not exist yet during early startup/migration ??? fail open.
            log.debug("edge_gate.demotions_unavailable {}", ex.getMessage());
            return cached != null ? cached.keys() : Set.of();
        }
    }

    public boolean isDemoted(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return false;
        }
        return activeDemotions().contains(strategyKey.trim().toUpperCase(Locale.ROOT));
    }

    /** Re-evaluates the rolling edge for every strategy and applies/lifts demotions. */
    public void evaluateGate() {
        if (!gateEnabled) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT strategy_name,
                       SUM(target_first + sl_first)            AS resolved,
                       SUM(target_first)                       AS target_first,
                       AVG(avg_rr)                             AS avg_rr
                FROM strategy_entry_edge_daily
                WHERE session_date >= (
                    SELECT COALESCE(MIN(session_date), CURRENT_DATE) FROM (
                        SELECT DISTINCT session_date FROM strategy_entry_edge_daily
                        ORDER BY session_date DESC LIMIT ?
                    ) recent)
                GROUP BY strategy_name
                """, windowSessions);

        for (Map<String, Object> row : rows) {
            String strategy = String.valueOf(row.get("strategy_name"));
            long resolved = row.get("resolved") == null ? 0 : ((Number) row.get("resolved")).longValue();
            long targetFirst = row.get("target_first") == null ? 0 : ((Number) row.get("target_first")).longValue();
            double avgRr = row.get("avg_rr") == null ? 1.0 : ((Number) row.get("avg_rr")).doubleValue();

            if (resolved < minSignals) {
                continue;
            }
            double targetFirstPct = 100.0 * targetFirst / resolved;
            double breakevenPct = 100.0 / (1.0 + Math.max(avgRr, 0.01));
            boolean demoted = isDemoted(strategy);

            if (!demoted && targetFirstPct < breakevenPct + demoteBufferPct) {
                String reason = String.format(Locale.ROOT,
                        "ROLLING_EDGE_BELOW_BREAKEVEN: targetFirst=%.1f%% breakeven=%.1f%% buffer=%.1f rr=%.2f resolved=%d window=%d sessions",
                        targetFirstPct, breakevenPct, demoteBufferPct, avgRr, resolved, windowSessions);
                jdbcTemplate.update("""
                        INSERT INTO strategy_edge_demotions (strategy_key, active, reason)
                        VALUES (?, true, ?)
                        ON CONFLICT (strategy_key) WHERE active = true DO NOTHING
                        """, strategy, reason);
                log.warn("edge_gate.demoted strategy={} {}", strategy, reason);
            } else if (demoted && targetFirstPct >= breakevenPct + liftBufferPct) {
                jdbcTemplate.update("""
                        UPDATE strategy_edge_demotions
                        SET active = false, lifted_at = now(), updated_at = now()
                        WHERE strategy_key = ? AND active = true
                        """, strategy);
                log.warn("edge_gate.lifted strategy={} targetFirst={}% breakeven={}%",
                        strategy, String.format("%.1f", targetFirstPct), String.format("%.1f", breakevenPct));
            }
        }
        cache.set(null);
    }

    private record CachedDemotions(Set<String> keys, Instant fetchedAt) {
    }
}
