package com.stokr.bootstrap.controller;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Real-time performance dashboard API for monitoring signal performance by confidence bracket.
 * Shows: signals generated, executed, targets hit, stop losses, hit rates, and P&L.
 */
@RestController
@RequestMapping("/api/v1/admin/performance")
@RequiredArgsConstructor
@Slf4j
public class AdminPerformanceDashboardController {

    private final JdbcTemplate jdbcTemplate;

    /**
     * GET /api/v1/admin/performance/dashboard
     * Returns real-time performance metrics for all confidence brackets (70%, 80%, 90%)
     */
    @GetMapping("/dashboard")
    public DashboardResponse getDashboard() {
        try {
            log.info("📊 Fetching performance dashboard data");

            DashboardResponse response = new DashboardResponse();
            response.setTimestamp(System.currentTimeMillis());
            response.setTradeDate(LocalDate.now().toString());

            // Get summary metrics
            response.setSummary(getSummaryMetrics());

            // Get breakdown by confidence bracket
            response.setConfidenceBrackets(getConfidenceBracketMetrics());

            // Get hourly breakdown
            response.setHourlyBreakdown(getHourlyBreakdown());

            // Get top performers
            response.setTopPerformers(getTopPerformers());

            log.info("✅ Dashboard data fetched successfully");
            return response;

        } catch (Exception e) {
            log.error("❌ Error fetching dashboard data", e);
            return new DashboardResponse();
        }
    }

    /**
     * Get overall summary metrics for today
     */
    private SummaryMetrics getSummaryMetrics() {
        String sql = """
            SELECT
              COUNT(*) as total_signals,
              COUNT(CASE WHEN order_executed THEN 1 END) as executed,
              COUNT(CASE WHEN target_hit THEN 1 END) as targets_hit,
              COUNT(CASE WHEN sl_hit THEN 1 END) as sl_hits,
              COUNT(CASE WHEN still_open THEN 1 END) as still_open,
              COALESCE(ROUND(AVG(final_pnl), 2), 0) as avg_pnl,
              COALESCE(ROUND(SUM(final_pnl), 2), 0) as total_pnl,
              COALESCE(ROUND(
                COUNT(CASE WHEN target_hit THEN 1 END)::numeric /
                NULLIF(COUNT(CASE WHEN order_executed THEN 1 END), 0) * 100, 2
              ), 0) as hit_rate_pct
            FROM daily_signal_performance
            WHERE signal_generated_at >= CURRENT_DATE
            """;

        Map<String, Object> result = jdbcTemplate.queryForMap(sql);

        SummaryMetrics metrics = new SummaryMetrics();
        metrics.setTotalSignals(((Number) result.get("total_signals")).intValue());
        metrics.setExecuted(((Number) result.get("executed")).intValue());
        metrics.setTargetsHit(((Number) result.get("targets_hit")).intValue());
        metrics.setSlHits(((Number) result.get("sl_hits")).intValue());
        metrics.setStillOpen(((Number) result.get("still_open")).intValue());
        metrics.setAvgPnl(((Number) result.get("avg_pnl")).doubleValue());
        metrics.setTotalPnl(((Number) result.get("total_pnl")).doubleValue());
        metrics.setHitRatePct(((Number) result.get("hit_rate_pct")).doubleValue());

        return metrics;
    }

    /**
     * Get metrics broken down by confidence bracket
     * Shows: 70%, 80%, 90%+ with hit rates and P&L
     */
    private List<ConfidenceBracketMetric> getConfidenceBracketMetrics() {
        String sql = """
            SELECT
              CASE
                WHEN confidence_score >= 90 THEN '90%+'
                WHEN confidence_score >= 80 THEN '80-89%'
                WHEN confidence_score >= 70 THEN '70-79%'
                WHEN confidence_score >= 60 THEN '60-69%'
                WHEN confidence_score >= 55 THEN '55-59%'
              END as bracket,
              confidence_score as min_confidence,
              COUNT(*) as signals,
              COUNT(CASE WHEN order_executed THEN 1 END) as executed,
              COUNT(CASE WHEN target_hit THEN 1 END) as targets_hit,
              COUNT(CASE WHEN sl_hit THEN 1 END) as sl_hits,
              COUNT(CASE WHEN still_open THEN 1 END) as still_open,
              COALESCE(ROUND(
                COUNT(CASE WHEN target_hit THEN 1 END)::numeric /
                NULLIF(COUNT(CASE WHEN order_executed THEN 1 END), 0) * 100, 2
              ), 0) as hit_rate_pct,
              COALESCE(ROUND(AVG(final_pnl), 2), 0) as avg_pnl,
              COALESCE(ROUND(SUM(final_pnl), 2), 0) as total_pnl,
              COUNT(CASE WHEN final_pnl > 0 THEN 1 END) as winners,
              COUNT(CASE WHEN final_pnl < 0 THEN 1 END) as losers
            FROM daily_signal_performance
            WHERE signal_generated_at >= CURRENT_DATE
            GROUP BY confidence_score
            ORDER BY confidence_score DESC
            """;

        List<ConfidenceBracketMetric> brackets = new ArrayList<>();

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);

            for (Map<String, Object> row : results) {
                ConfidenceBracketMetric metric = new ConfidenceBracketMetric();
                metric.setBracket((String) row.get("bracket"));
                metric.setMinConfidence(((Number) row.get("min_confidence")).intValue());
                metric.setSignals(((Number) row.get("signals")).intValue());
                metric.setExecuted(((Number) row.get("executed")).intValue());
                metric.setTargetsHit(((Number) row.get("targets_hit")).intValue());
                metric.setSlHits(((Number) row.get("sl_hits")).intValue());
                metric.setStillOpen(((Number) row.get("still_open")).intValue());
                metric.setHitRatePct(((Number) row.get("hit_rate_pct")).doubleValue());
                metric.setAvgPnl(((Number) row.get("avg_pnl")).doubleValue());
                metric.setTotalPnl(((Number) row.get("total_pnl")).doubleValue());
                metric.setWinners(((Number) row.get("winners")).intValue());
                metric.setLosers(((Number) row.get("losers")).intValue());

                brackets.add(metric);
            }
        } catch (Exception e) {
            log.warn("Error fetching confidence bracket metrics", e);
        }

        return brackets;
    }

    /**
     * Get hourly breakdown for today
     */
    private List<HourlyMetric> getHourlyBreakdown() {
        String sql = """
            SELECT
              (DATE_TRUNC('hour', signal_generated_at) AT TIME ZONE 'Asia/Kolkata')::text as hour,
              COUNT(*) as signals,
              COUNT(CASE WHEN order_executed THEN 1 END) as executed,
              COUNT(CASE WHEN target_hit THEN 1 END) as targets_hit,
              COUNT(CASE WHEN sl_hit THEN 1 END) as sl_hits,
              COALESCE(ROUND(AVG(final_pnl), 2), 0) as avg_pnl,
              COALESCE(ROUND(SUM(final_pnl), 2), 0) as total_pnl,
              COUNT(CASE WHEN final_pnl > 0 THEN 1 END) as winners
            FROM daily_signal_performance
            WHERE signal_generated_at >= CURRENT_DATE
            GROUP BY DATE_TRUNC('hour', signal_generated_at)
            ORDER BY hour DESC
            """;

        List<HourlyMetric> hourly = new ArrayList<>();

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);

            for (Map<String, Object> row : results) {
                HourlyMetric metric = new HourlyMetric();
                metric.setHour((String) row.get("hour"));
                metric.setSignals(((Number) row.get("signals")).intValue());
                metric.setExecuted(((Number) row.get("executed")).intValue());
                metric.setTargetsHit(((Number) row.get("targets_hit")).intValue());
                metric.setSlHits(((Number) row.get("sl_hits")).intValue());
                metric.setAvgPnl(((Number) row.get("avg_pnl")).doubleValue());
                metric.setTotalPnl(((Number) row.get("total_pnl")).doubleValue());
                metric.setWinners(((Number) row.get("winners")).intValue());

                hourly.add(metric);
            }
        } catch (Exception e) {
            log.warn("Error fetching hourly metrics", e);
        }

        return hourly;
    }

    /**
     * Get top performing stocks
     */
    private List<TopPerformer> getTopPerformers() {
        String sql = """
            SELECT
              symbol,
              COUNT(*) as signals,
              COUNT(CASE WHEN target_hit THEN 1 END) as targets_hit,
              COALESCE(ROUND(AVG(final_pnl), 2), 0) as avg_pnl,
              COALESCE(ROUND(SUM(final_pnl), 2), 0) as total_pnl,
              COALESCE(ROUND(AVG(confidence_score), 0), 0) as avg_confidence
            FROM daily_signal_performance
            WHERE signal_generated_at >= CURRENT_DATE
            GROUP BY symbol
            ORDER BY total_pnl DESC
            LIMIT 10
            """;

        List<TopPerformer> performers = new ArrayList<>();

        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);

            for (Map<String, Object> row : results) {
                TopPerformer performer = new TopPerformer();
                performer.setSymbol((String) row.get("symbol"));
                performer.setSignals(((Number) row.get("signals")).intValue());
                performer.setTargetsHit(((Number) row.get("targets_hit")).intValue());
                performer.setAvgPnl(((Number) row.get("avg_pnl")).doubleValue());
                performer.setTotalPnl(((Number) row.get("total_pnl")).doubleValue());
                performer.setAvgConfidence(((Number) row.get("avg_confidence")).intValue());

                performers.add(performer);
            }
        } catch (Exception e) {
            log.warn("Error fetching top performers", e);
        }

        return performers;
    }

    // ======================== DTOs ========================

    @Data
    public static class DashboardResponse {
        private long timestamp;
        private String tradeDate;
        private SummaryMetrics summary;
        private List<ConfidenceBracketMetric> confidenceBrackets;
        private List<HourlyMetric> hourlyBreakdown;
        private List<TopPerformer> topPerformers;
    }

    @Data
    public static class SummaryMetrics {
        private int totalSignals;
        private int executed;
        private int targetsHit;
        private int slHits;
        private int stillOpen;
        private double avgPnl;
        private double totalPnl;
        private double hitRatePct;
    }

    @Data
    public static class ConfidenceBracketMetric {
        private String bracket;        // "70-79%", "80-89%", "90%+"
        private int minConfidence;
        private int signals;           // Total signals in this bracket
        private int executed;          // How many executed
        private int targetsHit;        // How many hit take profit
        private int slHits;            // How many hit stop loss
        private int stillOpen;         // Still open positions
        private double hitRatePct;     // % of executed that hit target
        private double avgPnl;         // Average P&L for this bracket
        private double totalPnl;       // Total P&L for this bracket
        private int winners;           // # with positive P&L
        private int losers;            // # with negative P&L
    }

    @Data
    public static class HourlyMetric {
        private String hour;           // "2026-06-12 10:00:00+05:30"
        private int signals;
        private int executed;
        private int targetsHit;
        private int slHits;
        private double avgPnl;
        private double totalPnl;
        private int winners;
    }

    @Data
    public static class TopPerformer {
        private String symbol;
        private int signals;
        private int targetsHit;
        private double avgPnl;
        private double totalPnl;
        private int avgConfidence;
    }
}
