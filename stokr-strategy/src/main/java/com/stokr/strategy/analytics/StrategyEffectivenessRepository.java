package com.stokr.strategy.analytics;

import com.stokr.common.simulation.AnalyticsDataScope;
import com.stokr.common.simulation.SimulationAnalyticsFilters;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class StrategyEffectivenessRepository {

    private static String signalScopeFilter(AnalyticsDataScope scope) {
        return "s.deleted = FALSE AND " + SimulationAnalyticsFilters.signalScopeFilter(scope);
    }

    /** Explicit concatenation ??? text-block trailing space after WHERE is stripped and yields invalid SQL (WHEREs.). */
    private static String whereWithScope(AnalyticsDataScope scope) {
        return "WHERE " + signalScopeFilter(scope);
    }

    @PersistenceContext
    private EntityManager entityManager;

    @SuppressWarnings("unchecked")
    public List<Object[]> scorecardByStrategy(Instant from, Instant toExclusive) {
        return scorecardByStrategy(from, toExclusive, AnalyticsDataScope.REAL);
    }

    public List<Object[]> scorecardByStrategy(Instant from, Instant toExclusive, AnalyticsDataScope scope) {
        String sql = """
                SELECT
                    COALESCE(s.strategy_name, 'UNKNOWN') AS strategy_name,
                    COUNT(*)::bigint AS signals_generated,
                    COUNT(DISTINCT o.id) FILTER (
                        WHERE o.id IS NOT NULL AND o.deleted = FALSE
                          AND o.state IN ('FILLED', 'PARTIALLY_FILLED', 'EXIT_FILLED')
                    )::bigint AS signals_executed,
                    COUNT(*) FILTER (WHERE s.outcome_status = 'TARGET_HIT')::bigint AS target_hits,
                    COUNT(*) FILTER (WHERE s.outcome_status IN ('STOPLOSS_HIT', 'SL_HIT'))::bigint AS stop_loss_hits,
                    COUNT(*) FILTER (WHERE s.outcome_status IN (
                        'PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'BREAKEVEN_EXIT'))::bigint AS protection_exits,
                    COUNT(*) FILTER (WHERE s.outcome_status = 'FEED_PROTECTION')::bigint AS feed_protection,
                    COUNT(*) FILTER (WHERE s.outcome_status IN ('EXPIRED', 'TIME_EXIT'))::bigint AS expired,
                    COUNT(*) FILTER (WHERE s.outcome_status IS NULL OR s.outcome_status = 'PENDING')::bigint AS pending,
                    COUNT(*) FILTER (WHERE s.outcome_status = 'RUNNING')::bigint AS running,
                    COUNT(*) FILTER (WHERE s.outcome_status IN (
                        'TARGET_HIT', 'STOPLOSS_HIT', 'SL_HIT', 'PRESSURE_EXIT', 'LIQUIDITY_PROTECTION',
                        'FEED_PROTECTION', 'BREAKEVEN_EXIT', 'EXPIRED', 'TIME_EXIT', 'MANUAL'))::bigint AS closed,
                    COUNT(*) FILTER (WHERE s.outcome_status = 'RUNNING'
                        OR ((s.outcome_status IS NULL OR s.outcome_status = 'PENDING') AND s.entry_price IS NOT NULL))::bigint AS open_count,
                    AVG(s.confidence_score) FILTER (WHERE s.confidence_score IS NOT NULL) AS avg_confidence,
                    AVG(s.probability) FILTER (WHERE s.probability IS NOT NULL) AS avg_probability,
                    AVG(s.risk_reward_achieved) FILTER (WHERE s.risk_reward_achieved IS NOT NULL) AS avg_rr,
                    AVG(EXTRACT(EPOCH FROM (COALESCE(s.outcome_time, s.updated_at) - s.created_at)))
                        FILTER (WHERE s.outcome_status IS NOT NULL AND s.outcome_status NOT IN ('PENDING', 'RUNNING')) AS avg_hold_sec,
                    AVG(s.max_favorable_excursion) FILTER (WHERE s.max_favorable_excursion IS NOT NULL) AS avg_mfe,
                    AVG(s.max_adverse_excursion) FILTER (WHERE s.max_adverse_excursion IS NOT NULL) AS avg_mae,
                    MAX(s.max_favorable_excursion) AS max_mfe,
                    MAX(s.max_adverse_excursion) AS max_mae,
                    COUNT(*) FILTER (WHERE s.hit_target = TRUE OR s.outcome_status = 'TARGET_HIT')::bigint AS target_reach,
                    COUNT(*) FILTER (WHERE s.hit_stoploss = TRUE OR s.outcome_status IN ('STOPLOSS_HIT', 'SL_HIT'))::bigint AS stop_reach,
                    COUNT(*) FILTER (WHERE s.realized_pnl IS NOT NULL AND s.realized_pnl > 0)::bigint AS wins,
                    COUNT(*) FILTER (WHERE s.realized_pnl IS NOT NULL AND s.realized_pnl < 0)::bigint AS losses,
                    SUM(s.realized_pnl) FILTER (WHERE s.realized_pnl > 0) AS gross_profit,
                    SUM(ABS(s.realized_pnl)) FILTER (WHERE s.realized_pnl < 0) AS gross_loss,
                    AVG(s.realized_pnl) FILTER (WHERE s.realized_pnl IS NOT NULL) AS avg_pnl,
                    COUNT(*) FILTER (WHERE s.confidence_version = 'CONFIDENCE_V2')::bigint AS confidence_v2_count,
                    COUNT(*) FILTER (WHERE s.confidence_score IS NULL)::bigint AS confidence_null_count
                FROM strategy_signals s
                LEFT JOIN oms_orders o ON o.signal_id = s.id
                """ + whereWithScope(scope) + """
                  AND s.created_at >= :from
                  AND s.created_at < :toExclusive
                GROUP BY s.strategy_name
                ORDER BY signals_generated DESC
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("from", from);
        q.setParameter("toExclusive", toExclusive);
        return q.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> rejectionsByStrategy(Instant from, Instant toExclusive) {
        return rejectionsByStrategy(from, toExclusive, AnalyticsDataScope.REAL);
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> rejectionsByStrategy(Instant from, Instant toExclusive, AnalyticsDataScope scope) {
        String sql = """
                SELECT strategy_key, COUNT(*)::bigint
                FROM signal_pipeline_audit
                WHERE created_at >= :from AND created_at < :toExclusive
                  AND (execution_status = 'REJECTED' OR pipeline_stage IN ('REJECTED', 'DEDUP', 'QUALITY_GATE', 'SESSION_CHECK'))
                GROUP BY strategy_key
                ORDER BY COUNT(*) DESC
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("from", from);
        q.setParameter("toExclusive", toExclusive);
        return q.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> confidenceBuckets(Instant from, Instant toExclusive, String strategyName) {
        String strategyFilter = strategyName == null || strategyName.isBlank()
                ? "" : " AND upper(s.strategy_name) = upper(:strategyName) ";
        String sql = """
                SELECT
                    CASE
                        WHEN s.confidence_score IS NULL THEN 'NULL'
                        WHEN s.confidence_score * 100 <= 20 THEN '0-20'
                        WHEN s.confidence_score * 100 <= 40 THEN '21-40'
                        WHEN s.confidence_score * 100 <= 60 THEN '41-60'
                        WHEN s.confidence_score * 100 <= 80 THEN '61-80'
                        ELSE '81-100'
                    END AS bucket,
                    COUNT(*)::bigint AS signals,
                    COUNT(*) FILTER (WHERE s.realized_pnl > 0)::bigint AS wins,
                    COUNT(*) FILTER (WHERE s.realized_pnl < 0)::bigint AS losses,
                    COUNT(*) FILTER (WHERE s.outcome_status IN (
                        'PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'BREAKEVEN_EXIT', 'FEED_PROTECTION'))::bigint AS protection_exits,
                    COUNT(*) FILTER (WHERE s.outcome_status = 'TARGET_HIT')::bigint AS target_hits,
                    COUNT(*) FILTER (WHERE s.outcome_status IN ('STOPLOSS_HIT', 'SL_HIT'))::bigint AS sl_hits
                FROM strategy_signals s
                """ + whereWithScope(AnalyticsDataScope.REAL) + """
                  AND s.created_at >= :from
                  AND s.created_at < :toExclusive
                """ + strategyFilter + """
                GROUP BY 1
                ORDER BY 1
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("from", from);
        q.setParameter("toExclusive", toExclusive);
        if (strategyName != null && !strategyName.isBlank()) {
            q.setParameter("strategyName", strategyName.trim());
        }
        return q.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> protectionImpactByStrategy(Instant from, Instant toExclusive) {
        String sql = """
                SELECT
                    COALESCE(s.strategy_name, 'UNKNOWN') AS strategy_name,
                    COUNT(*) FILTER (WHERE s.outcome_status IN (
                        'PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'BREAKEVEN_EXIT', 'FEED_PROTECTION'))::bigint AS protected_trades,
                    COUNT(*) FILTER (WHERE s.outcome_status IN (
                        'PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'BREAKEVEN_EXIT', 'FEED_PROTECTION')
                        AND (
                            s.hit_target = TRUE
                            OR (s.entry_price IS NOT NULL AND s.target_price IS NOT NULL
                                AND s.max_favorable_excursion IS NOT NULL
                                AND s.max_favorable_excursion >= ABS(s.target_price - s.entry_price))
                        ))::bigint AS would_have_hit_target,
                    COUNT(*) FILTER (WHERE s.outcome_status IN (
                        'PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'BREAKEVEN_EXIT', 'FEED_PROTECTION')
                        AND (
                            s.hit_stoploss = TRUE
                            OR (s.entry_price IS NOT NULL AND s.stop_price IS NOT NULL
                                AND s.max_adverse_excursion IS NOT NULL
                                AND s.max_adverse_excursion >= ABS(s.entry_price - s.stop_price))
                        ))::bigint AS would_have_hit_stop
                FROM strategy_signals s
                """ + whereWithScope(AnalyticsDataScope.REAL) + """
                  AND s.created_at >= :from
                  AND s.created_at < :toExclusive
                GROUP BY s.strategy_name
                ORDER BY protected_trades DESC
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("from", from);
        q.setParameter("toExclusive", toExclusive);
        return q.getResultList();
    }

    @SuppressWarnings("unchecked")
    public Object[] periodComparison(Instant from, Instant toExclusive, Instant v8Cutoff) {
        String sql = """
                SELECT
                    COUNT(*) FILTER (WHERE s.created_at < :v8Cutoff)::bigint AS pre_signals,
                    COUNT(*) FILTER (WHERE s.created_at >= :v8Cutoff)::bigint AS post_signals,
                    COUNT(*) FILTER (WHERE s.created_at < :v8Cutoff AND s.outcome_status = 'TARGET_HIT')::bigint AS pre_target,
                    COUNT(*) FILTER (WHERE s.created_at >= :v8Cutoff AND s.outcome_status = 'TARGET_HIT')::bigint AS post_target,
                    COUNT(*) FILTER (WHERE s.created_at < :v8Cutoff AND s.outcome_status IN ('STOPLOSS_HIT', 'SL_HIT'))::bigint AS pre_sl,
                    COUNT(*) FILTER (WHERE s.created_at >= :v8Cutoff AND s.outcome_status IN ('STOPLOSS_HIT', 'SL_HIT'))::bigint AS post_sl,
                    COUNT(*) FILTER (WHERE s.created_at < :v8Cutoff AND s.outcome_status IN (
                        'PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'BREAKEVEN_EXIT', 'FEED_PROTECTION'))::bigint AS pre_protection,
                    COUNT(*) FILTER (WHERE s.created_at >= :v8Cutoff AND s.outcome_status IN (
                        'PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'BREAKEVEN_EXIT', 'FEED_PROTECTION'))::bigint AS post_protection,
                    AVG(EXTRACT(EPOCH FROM (COALESCE(s.outcome_time, s.updated_at) - s.created_at)))
                        FILTER (WHERE s.created_at < :v8Cutoff AND s.outcome_status NOT IN ('PENDING', 'RUNNING')) AS pre_avg_hold,
                    AVG(EXTRACT(EPOCH FROM (COALESCE(s.outcome_time, s.updated_at) - s.created_at)))
                        FILTER (WHERE s.created_at >= :v8Cutoff AND s.outcome_status NOT IN ('PENDING', 'RUNNING')) AS post_avg_hold,
                    COUNT(*) FILTER (WHERE s.created_at < :v8Cutoff AND s.confidence_score IS NOT NULL)::bigint AS pre_conf_populated,
                    COUNT(*) FILTER (WHERE s.created_at >= :v8Cutoff AND s.confidence_score IS NOT NULL)::bigint AS post_conf_populated,
                    COUNT(*) FILTER (WHERE s.created_at >= :v8Cutoff AND s.confidence_version = 'CONFIDENCE_V2')::bigint AS post_conf_v2,
                    COUNT(*) FILTER (WHERE s.created_at < :v8Cutoff AND s.max_favorable_excursion IS NOT NULL)::bigint AS pre_mfe_tracked,
                    COUNT(*) FILTER (WHERE s.created_at >= :v8Cutoff AND s.max_favorable_excursion IS NOT NULL)::bigint AS post_mfe_tracked
                FROM strategy_signals s
                """ + whereWithScope(AnalyticsDataScope.REAL) + """
                  AND s.created_at >= :from
                  AND s.created_at < :toExclusive
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("from", from);
        q.setParameter("toExclusive", toExclusive);
        q.setParameter("v8Cutoff", v8Cutoff);
        return (Object[]) q.getSingleResult();
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> alphaAttributionByStrategy(Instant from, Instant toExclusive) {
        String sql = """
                SELECT
                    COALESCE(s.strategy_name, 'UNKNOWN'),
                    COUNT(*)::bigint,
                    COUNT(DISTINCT o.id) FILTER (
                        WHERE o.id IS NOT NULL AND o.deleted = FALSE
                          AND o.state IN ('FILLED', 'PARTIALLY_FILLED', 'EXIT_FILLED')
                    )::bigint,
                    COUNT(*) FILTER (WHERE s.outcome_status = 'TARGET_HIT')::bigint,
                    COUNT(*) FILTER (WHERE s.outcome_status IN ('STOPLOSS_HIT', 'SL_HIT'))::bigint,
                    COUNT(*) FILTER (WHERE s.outcome_status IN (
                        'PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'BREAKEVEN_EXIT', 'FEED_PROTECTION'))::bigint,
                    AVG(EXTRACT(EPOCH FROM (COALESCE(s.outcome_time, s.updated_at) - s.created_at)))
                        FILTER (WHERE s.outcome_status NOT IN ('PENDING', 'RUNNING')) AS avg_hold_sec,
                    AVG(s.confidence_score) FILTER (WHERE s.confidence_score IS NOT NULL),
                    AVG(s.probability) FILTER (WHERE s.probability IS NOT NULL),
                    COALESCE(SUM(s.realized_pnl), 0),
                    COALESCE(SUM(
                        CASE
                            WHEN s.realized_pnl IS NOT NULL THEN s.realized_pnl
                            WHEN s.outcome_status = 'TARGET_HIT'
                                 AND s.entry_price IS NOT NULL AND s.target_price IS NOT NULL THEN
                                (CASE WHEN s.signal_type = 'BUY'
                                      THEN s.target_price - s.entry_price
                                      ELSE s.entry_price - s.target_price END)
                                * COALESCE(s.suggested_qty, 1)
                            WHEN s.outcome_status IN ('STOPLOSS_HIT', 'SL_HIT')
                                 AND s.entry_price IS NOT NULL AND s.stop_price IS NOT NULL THEN
                                (CASE WHEN s.signal_type = 'BUY'
                                      THEN s.stop_price - s.entry_price
                                      ELSE s.entry_price - s.stop_price END)
                                * COALESCE(s.suggested_qty, 1)
                            ELSE 0
                        END
                    ), 0),
                    AVG(s.realized_pnl) FILTER (WHERE s.realized_pnl IS NOT NULL),
                    COALESCE(SUM(s.realized_pnl) FILTER (WHERE s.realized_pnl > 0), 0),
                    COALESCE(SUM(ABS(s.realized_pnl)) FILTER (WHERE s.realized_pnl < 0), 0)
                FROM strategy_signals s
                LEFT JOIN oms_orders o ON o.signal_id = s.id
                """ + whereWithScope(AnalyticsDataScope.REAL) + """
                  AND s.created_at >= :from
                  AND s.created_at < :toExclusive
                GROUP BY s.strategy_name
                ORDER BY COUNT(*) DESC
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("from", from);
        q.setParameter("toExclusive", toExclusive);
        return q.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> protectionRemovalByStrategy(Instant from, Instant toExclusive) {
        String protectedFilter =
                "s.outcome_status IN ('PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'BREAKEVEN_EXIT', 'FEED_PROTECTION')";
        String wouldTarget =
                "(s.hit_target = TRUE OR (s.entry_price IS NOT NULL AND s.target_price IS NOT NULL "
                        + "AND s.max_favorable_excursion IS NOT NULL "
                        + "AND s.max_favorable_excursion >= ABS(s.target_price - s.entry_price)))";
        String wouldStop =
                "(s.hit_stoploss = TRUE OR (s.entry_price IS NOT NULL AND s.stop_price IS NOT NULL "
                        + "AND s.max_adverse_excursion IS NOT NULL "
                        + "AND s.max_adverse_excursion >= ABS(s.entry_price - s.stop_price)))";
        String sql =
                "SELECT COALESCE(s.strategy_name, 'UNKNOWN'), "
                        + "COUNT(*) FILTER (WHERE " + protectedFilter + ")::bigint, "
                        + "COUNT(*) FILTER (WHERE " + protectedFilter + " AND " + wouldTarget + ")::bigint, "
                        + "COUNT(*) FILTER (WHERE " + protectedFilter + " AND " + wouldStop + ")::bigint, "
                        + "COUNT(*) FILTER (WHERE " + protectedFilter + " AND NOT (" + wouldTarget + ") AND NOT ("
                        + wouldStop + "))::bigint, "
                        + "COUNT(*) FILTER (WHERE " + protectedFilter
                        + " AND s.realized_pnl IS NOT NULL AND s.realized_pnl > 0)::bigint, "
                        + "COUNT(*) FILTER (WHERE " + protectedFilter
                        + " AND s.realized_pnl IS NOT NULL AND s.realized_pnl < 0)::bigint, "
                        + "COALESCE(SUM(CASE WHEN " + protectedFilter + " AND " + wouldTarget
                        + " AND s.entry_price IS NOT NULL AND s.target_price IS NOT NULL "
                        + "THEN ABS(s.target_price - s.entry_price) * COALESCE(s.suggested_qty, 1) ELSE 0 END), 0), "
                        + "COALESCE(SUM(CASE WHEN " + protectedFilter + " AND " + wouldStop
                        + " AND s.entry_price IS NOT NULL AND s.stop_price IS NOT NULL "
                        + "THEN ABS(s.entry_price - s.stop_price) * COALESCE(s.suggested_qty, 1) ELSE 0 END), 0) "
                        + "FROM strategy_signals s WHERE " + signalScopeFilter(AnalyticsDataScope.REAL)
                        + " AND s.created_at >= :from AND s.created_at < :toExclusive "
                        + "GROUP BY s.strategy_name HAVING COUNT(*) FILTER (WHERE " + protectedFilter + ") > 0 "
                        + "ORDER BY COUNT(*) FILTER (WHERE " + protectedFilter + ") DESC";
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("from", from);
        q.setParameter("toExclusive", toExclusive);
        return q.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> confidenceValidationBuckets(Instant from, Instant toExclusive, String strategyName) {
        return confidenceValidationQuery(from, toExclusive, strategyName, null);
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> confidenceValidationBucketsPostV8(Instant from, Instant toExclusive, Instant v8Cutoff) {
        return confidenceValidationQuery(from, toExclusive, null, v8Cutoff);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> confidenceValidationQuery(
            Instant from,
            Instant toExclusive,
            String strategyName,
            Instant v8Cutoff
    ) {
        String strategyFilter = strategyName == null || strategyName.isBlank()
                ? "" : " AND upper(s.strategy_name) = upper(:strategyName) ";
        String v8Filter = v8Cutoff == null ? "" : " AND s.created_at >= :v8Cutoff AND s.confidence_version = 'CONFIDENCE_V2' ";
        String sql = """
                SELECT
                    CASE
                        WHEN s.confidence_score IS NULL THEN 'NULL'
                        WHEN s.confidence_score * 100 <= 20 THEN '0-20'
                        WHEN s.confidence_score * 100 <= 40 THEN '21-40'
                        WHEN s.confidence_score * 100 <= 60 THEN '41-60'
                        WHEN s.confidence_score * 100 <= 80 THEN '61-80'
                        ELSE '81-100'
                    END AS bucket,
                    COUNT(*)::bigint,
                    COUNT(*) FILTER (WHERE s.realized_pnl > 0)::bigint,
                    COUNT(*) FILTER (WHERE s.realized_pnl < 0)::bigint,
                    COUNT(*) FILTER (WHERE s.outcome_status = 'TARGET_HIT')::bigint,
                    COUNT(*) FILTER (WHERE s.outcome_status IN ('STOPLOSS_HIT', 'SL_HIT'))::bigint,
                    COALESCE(SUM(s.realized_pnl) FILTER (WHERE s.realized_pnl > 0), 0),
                    COALESCE(SUM(ABS(s.realized_pnl)) FILTER (WHERE s.realized_pnl < 0), 0),
                    AVG(s.realized_pnl) FILTER (WHERE s.realized_pnl IS NOT NULL)
                FROM strategy_signals s
                """ + whereWithScope(AnalyticsDataScope.REAL) + """
                  AND s.created_at >= :from
                  AND s.created_at < :toExclusive
                """ + strategyFilter + v8Filter + """
                GROUP BY 1
                ORDER BY 1
                """;
        Query q = entityManager.createNativeQuery(sql);
        q.setParameter("from", from);
        q.setParameter("toExclusive", toExclusive);
        if (strategyName != null && !strategyName.isBlank()) {
            q.setParameter("strategyName", strategyName.trim());
        }
        if (v8Cutoff != null) {
            q.setParameter("v8Cutoff", v8Cutoff);
        }
        return q.getResultList();
    }
}
