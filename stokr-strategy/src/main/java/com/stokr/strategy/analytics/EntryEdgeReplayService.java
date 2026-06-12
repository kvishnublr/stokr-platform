package com.stokr.strategy.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Nightly counterfactual replay of every emitted signal against stored 1m candles:
 * from entry, which level was touched first — target or stop — ignoring all
 * exit-engine interference?
 *
 * This is the metric that separates bad entries from bad exits. The May-Jun 2026
 * audit found ADV_CASH at 56.9% target-first (genuine edge, killed by noise exits)
 * while NSE_SPIKE_DETECTION sat at 27.8% across 792 trades (no exit logic can save
 * it). Results land in strategy_entry_edge_daily; the demotion gate then compares
 * each strategy's rolling target-first rate to the breakeven rate implied by its
 * own average risk:reward and force-demotes persistent losers to PAPER.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EntryEdgeReplayService {

    private final JdbcTemplate jdbcTemplate;
    private final StrategyEdgeGateService edgeGateService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.entry-edge.enabled:true}")
    private boolean enabled;

    /** Replay after the equity close once the session's candles are complete. */
    @Scheduled(cron = "${stokr.strategy.entry-edge.cron:0 50 15 * * MON-FRI}",
               zone = "${stokr.strategy.session.zone:Asia/Kolkata}")
    public void runNightlyReplay() {
        if (!enabled) {
            return;
        }
        LocalDate sessionDate = LocalDate.now(zone);
        try {
            int rows = replaySession(sessionDate);
            log.info("entry_edge.replay_done session={} strategies={}", sessionDate, rows);
            edgeGateService.evaluateGate();
        } catch (Exception ex) {
            log.error("entry_edge.replay_failed session={}", sessionDate, ex);
        }
    }

    /**
     * Replays one session and upserts per-strategy aggregates. Visible for ops re-runs.
     */
    public int replaySession(LocalDate sessionDate) {
        String sql = """
            WITH sigs AS (
              SELECT id, symbol, signal_type, strategy_name, created_at,
                     entry_price, target_price, stop_price,
                     ABS(target_price - entry_price) AS tgt_dist,
                     ABS(entry_price - stop_price)  AS sl_dist
              FROM strategy_signals
              WHERE deleted = false
                AND backtest_run_id IS NULL
                AND is_test_trade = false
                AND target_price IS NOT NULL AND stop_price IS NOT NULL
                AND entry_price IS NOT NULL
                AND entry_price <> stop_price
                AND (created_at AT TIME ZONE 'Asia/Kolkata')::date = ?::date
            ), cf AS (
              SELECT s.*, hit.first_hit
              FROM sigs s
              LEFT JOIN LATERAL (
                SELECT CASE
                         WHEN s.signal_type = 'BUY'  AND c.high_price >= s.target_price THEN 'TARGET'
                         WHEN s.signal_type = 'BUY'  AND c.low_price  <= s.stop_price   THEN 'SL'
                         WHEN s.signal_type = 'SELL' AND c.low_price  <= s.target_price THEN 'TARGET'
                         WHEN s.signal_type = 'SELL' AND c.high_price >= s.stop_price   THEN 'SL'
                       END AS first_hit
                FROM marketdata_candles c
                WHERE c.symbol = s.symbol
                  AND c.timeframe = '1m'
                  AND c.open_time > s.created_at
                  AND (c.open_time AT TIME ZONE 'Asia/Kolkata')::date =
                      (s.created_at AT TIME ZONE 'Asia/Kolkata')::date
                  AND ((s.signal_type = 'BUY'  AND (c.high_price >= s.target_price OR c.low_price  <= s.stop_price))
                    OR (s.signal_type = 'SELL' AND (c.low_price  <= s.target_price OR c.high_price >= s.stop_price)))
                ORDER BY c.open_time
                LIMIT 1
              ) hit ON true
            ), agg AS (
              SELECT strategy_name,
                COUNT(*)::int AS signals,
                COUNT(*) FILTER (WHERE first_hit = 'TARGET')::int AS target_first,
                COUNT(*) FILTER (WHERE first_hit = 'SL')::int     AS sl_first,
                COUNT(*) FILTER (WHERE first_hit IS NULL)::int    AS unresolved,
                AVG(tgt_dist / NULLIF(sl_dist, 0)) AS avg_rr,
                SUM(CASE WHEN first_hit = 'TARGET' THEN tgt_dist
                         WHEN first_hit = 'SL'     THEN -sl_dist
                         ELSE 0 END) AS replay_pnl
              FROM cf
              GROUP BY strategy_name
            )
            INSERT INTO strategy_entry_edge_daily (
                session_date, strategy_name, signals, target_first, sl_first, unresolved,
                avg_rr, target_first_pct, breakeven_pct, replay_pnl, updated_at)
            SELECT ?::date, strategy_name, signals, target_first, sl_first, unresolved,
                ROUND(avg_rr::numeric, 4),
                ROUND((100.0 * target_first / NULLIF(target_first + sl_first, 0))::numeric, 4),
                ROUND((100.0 / (1.0 + COALESCE(avg_rr, 1)))::numeric, 4),
                ROUND(replay_pnl::numeric, 4),
                now()
            FROM agg
            ON CONFLICT (strategy_name, session_date) DO UPDATE SET
                signals = EXCLUDED.signals,
                target_first = EXCLUDED.target_first,
                sl_first = EXCLUDED.sl_first,
                unresolved = EXCLUDED.unresolved,
                avg_rr = EXCLUDED.avg_rr,
                target_first_pct = EXCLUDED.target_first_pct,
                breakeven_pct = EXCLUDED.breakeven_pct,
                replay_pnl = EXCLUDED.replay_pnl,
                updated_at = now()
            """;
        return jdbcTemplate.update(sql, sessionDate.toString(), sessionDate.toString());
    }
}
