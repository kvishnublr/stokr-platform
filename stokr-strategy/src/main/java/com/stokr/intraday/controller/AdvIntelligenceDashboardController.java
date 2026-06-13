package com.stokr.intraday.controller;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.intraday.service.AdvIntelligenceFeedService;
import com.stokr.intraday.service.AdvIntelligenceTerminalService;
import com.stokr.intraday.service.LiveIntradayMoverService;
import com.stokr.intraday.stream.RealTimeSetupStream;
import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.auth.security.StokrUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/adv-dashboard")
@RequiredArgsConstructor
public class AdvIntelligenceDashboardController {

    private final RealTimeSetupStream realTimeStream;
    private final AdvIntelligenceFeedService feedService;
    private final AdvIntelligenceTerminalService terminalService;
    private final LiveIntradayMoverService liveMoverService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${stokr.strategy.execution-modes.live-validated:}")
    private String liveValidatedCsv;

    /**
     * Dashboard metrics endpoint for the enhanced frontend dashboard.
     * Returns current trading metrics including NIFTY price, active signals, P&L, and win rate.
     * Public endpoint - no authentication required for initial display.
     */
    @GetMapping("/dashboard-metrics")
    public ApiResponse<Map<String, Object>> dashboardMetrics(@AuthenticationPrincipal StokrUserDetails user) {
        var uid = user != null ? user.getId() : null;
        Map<String, Object> terminal = terminalService.buildTerminal(uid);

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) terminal.getOrDefault("metrics", Map.of());

        // Extract or calculate metrics for the dashboard
        int niftyPrice = metrics.get("niftyPrice") instanceof Number n ? n.intValue() : 20485;
        int todaySignals = metrics.get("todaySignals") instanceof Number n ? n.intValue() : 0;
        int todayPnL = metrics.get("todayPnL") instanceof Number n ? n.intValue() : 0;
        int winRate = metrics.get("winRate") instanceof Number n ? n.intValue() : 0;

        Map<String, Object> response = Map.of(
                "data", Map.of(
                        "niftyPrice", niftyPrice,
                        "todaySignals", todaySignals,
                        "todayPnL", todayPnL,
                        "winRate", winRate,
                        "timestamp", System.currentTimeMillis(),
                        "status", "live"
                )
        );

        return ApiResponse.ok(response, CorrelationIdHolder.get());
    }

    @GetMapping("/snapshot")
    @PreAuthorize("isAuthenticated()")
    public AdvDashboardSnapshot snapshot() {
        Map<String, Object> terminal = terminalService.buildTerminal(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) terminal.getOrDefault("metrics", Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scannerRows = (List<Map<String, Object>>) terminal.getOrDefault("scannerRows", List.of());

        List<SetupCardDto> setups = scannerRows.stream().map(this::toSetupCardFromRow).limit(23).toList();
        Map<String, Object> snapshotMetrics = new LinkedHashMap<>(metrics);
        snapshotMetrics.put("regime", terminal.getOrDefault("marketRegime", "CHOPPY"));

        return new AdvDashboardSnapshot(
                String.valueOf(terminal.getOrDefault("marketRegime", "CHOPPY")),
                String.valueOf(terminal.getOrDefault("regimeNarrative", "")),
                snapshotMetrics,
                setups,
                List.of(
                        "Price structure and volume expansion drive ranking — not raw indicators.",
                        "Only top-quality setups surface; weak signals are filtered.",
                        "Regime context adjusts confidence before any trade suggestion."
                )
        );
    }

    @GetMapping("/movers")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> movers() {
        return ApiResponse.ok(liveMoverService.liveWatchRows(25), CorrelationIdHolder.get());
    }

    @GetMapping("/terminal")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> terminal(@AuthenticationPrincipal StokrUserDetails user) {
        var uid = user != null ? user.getId() : null;
        return ApiResponse.ok(terminalService.buildTerminal(uid), CorrelationIdHolder.get());
    }

    /**
     * Edge & Go-Live panel: per-strategy rolling entry edge (counterfactual target-first %
     * vs the breakeven implied by its own R:R), active edge-gate demotions, the live
     * whitelist, and today's fill health — everything a trader needs to decide whether
     * a strategy deserves LIVE capital today.
     */
    @GetMapping("/edge")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> edge() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> liveWhitelist = liveValidatedCsv == null || liveValidatedCsv.isBlank()
                ? List.of()
                : List.of(liveValidatedCsv.split("\\s*,\\s*"));
        out.put("liveWhitelist", liveWhitelist);

        List<Map<String, Object>> edgeRows = jdbcTemplate.queryForList("""
                SELECT strategy_name,
                       count(*)                                  AS sessions,
                       sum(signals)                              AS signals,
                       sum(target_first)                         AS target_first,
                       sum(sl_first)                             AS sl_first,
                       round(avg(avg_rr)::numeric, 2)            AS avg_rr,
                       round((100.0 * sum(target_first) /
                              NULLIF(sum(target_first) + sum(sl_first), 0))::numeric, 1) AS target_first_pct,
                       round(avg(breakeven_pct)::numeric, 1)     AS breakeven_pct,
                       round(sum(replay_pnl)::numeric, 1)        AS replay_pnl
                FROM strategy_entry_edge_daily
                WHERE session_date >= current_date - interval '14 days'
                GROUP BY strategy_name
                ORDER BY target_first_pct DESC NULLS LAST
                """);
        for (Map<String, Object> row : edgeRows) {
            Object tf = row.get("target_first_pct");
            Object be = row.get("breakeven_pct");
            boolean hasEdge = tf instanceof Number t && be instanceof Number b
                    && t.doubleValue() > b.doubleValue();
            row.put("has_edge", hasEdge);
            row.put("live_whitelisted", liveWhitelist.contains(String.valueOf(row.get("strategy_name"))));
        }
        out.put("entryEdge", edgeRows);

        out.put("activeDemotions", jdbcTemplate.queryForList("""
                SELECT strategy_key, reason, demoted_at
                FROM strategy_edge_demotions
                WHERE active = true
                ORDER BY demoted_at DESC
                """));

        Map<String, Object> fills = jdbcTemplate.queryForList("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE state IN ('FILLED','PARTIALLY_FILLED')) AS filled,
                       count(*) FILTER (WHERE state IN ('REJECTED','FAILED'))         AS failed
                FROM oms_orders
                WHERE deleted = false
                  AND idempotency_key NOT LIKE 'outcome-exit:%'
                  AND created_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
                """).stream().findFirst().orElse(Map.of());
        out.put("todayOrders", fills);
        return ApiResponse.ok(out, CorrelationIdHolder.get());
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> refresh() {
        feedService.refreshNow();
        RealTimeSetupStream.StreamStatistics stats = realTimeStream.getStatistics();
        return ApiResponse.ok(Map.of(
                "refreshed", true,
                "tickCount", stats.tickCount,
                "setupCount", stats.rankingBoardSize
        ), CorrelationIdHolder.get());
    }

    private int topFromSetups(List<SetupCardDto> setups) {
        return setups.stream().mapToInt(SetupCardDto::confidenceScore).max().orElse(0);
    }

    private SetupCardDto toSetupCardFromRow(Map<String, Object> row) {
        int confidence = row.get("aiScore") instanceof Number n ? n.intValue() : 60;
        BigDecimal score = BigDecimal.valueOf(confidence);
        String tier = classifyTier(score);
        List<String> badges = new ArrayList<>();
        Object badgeList = row.get("badges");
        if (badgeList instanceof List<?> bl) {
            for (Object b : bl) {
                if (b != null) badges.add(String.valueOf(b));
            }
        }
        if (badges.isEmpty() && row.get("setupType") != null) {
            badges.add(String.valueOf(row.get("setupType")));
        }
        return new SetupCardDto(
                String.valueOf(row.get("symbol")),
                row.get("setupType") != null ? String.valueOf(row.get("setupType")) : "Setup",
                confidence,
                tier,
                badges,
                toBigDecimal(row.get("ltp")),
                toBigDecimal(row.get("targetPrice")),
                toBigDecimal(row.get("stopLoss")),
                null,
                String.valueOf(row.getOrDefault("executionStatus", row.getOrDefault("status", "WATCHLIST")))
                        + (row.get("rejectionReason") != null ? " — " + row.get("rejectionReason") : ""),
                "Invalidation if volume/OI confirmation weakens vs entry."
        );
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private SetupCardDto toSetupCard(CurrentSetup setup) {
        BigDecimal score = setup.getQualityScore() != null ? setup.getQualityScore() : BigDecimal.ZERO;
        int confidence = score.setScale(0, RoundingMode.HALF_UP).intValue();
        String tier = classifyTier(score);
        List<String> badges = new ArrayList<>();
        if (setup.getSetupType() != null) {
            badges.add(setup.getSetupType().replace('_', ' ').toUpperCase());
        }
        if (setup.getConfidenceLevel() != null) {
            badges.add(setup.getConfidenceLevel());
        }
        return new SetupCardDto(
                setup.getStockId(),
                setup.getSetupType(),
                confidence,
                tier,
                badges,
                setup.getEntryPrice(),
                setup.getTargetPrice(),
                setup.getStopLoss(),
                setup.getRiskRewardRatio(),
                buildWhyText(setup, tier),
                buildRiskText(setup)
        );
    }

    private static String classifyTier(BigDecimal score) {
        if (score == null) return "WEAK SETUP";
        int s = score.intValue();
        if (s >= 85) return "A+ SETUP";
        if (s >= 75) return "A SETUP";
        if (s >= 65) return "B SETUP";
        if (s >= 50) return "WEAK SETUP";
        return "HIGH RISK";
    }

    private static String buildWhyText(CurrentSetup setup, String tier) {
        StringBuilder sb = new StringBuilder();
        sb.append(tier).append(" — ");
        if (setup.getSetupType() != null) {
            sb.append(setup.getSetupType().replace('_', ' ')).append(" structure detected.");
        }
        return sb.toString().trim();
    }

    private static String buildRiskText(CurrentSetup setup) {
        if (setup.getStopLoss() != null) {
            return "Invalidation below " + setup.getStopLoss();
        }
        return "Reduce size if volume/OI confirmation weakens vs entry.";
    }

    private static String regimeDescription(MarketRegimeDetector.MarketRegime regime) {
        return switch (regime) {
            case TRENDING_UP -> "Trend day — breakouts and relative strength prioritized";
            case TRENDING_DOWN -> "Down-trend — defensive setups, fade rallies cautiously";
            case CHOPPY -> "Range/chop — mean reversion favored, breakouts down-ranked";
            case VOLATILE -> "High volatility — confidence scores compressed";
            case QUIET -> "Low participation — fewer actionable setups";
        };
    }

    public record AdvDashboardSnapshot(
            String marketRegime,
            String regimeNarrative,
            Map<String, Object> metrics,
            List<SetupCardDto> setups,
            List<String> principles) {
    }

    public record SetupCardDto(
            String symbol,
            String setupType,
            int confidenceScore,
            String qualityTier,
            List<String> badges,
            BigDecimal entryPrice,
            BigDecimal targetPrice,
            BigDecimal stopLoss,
            BigDecimal riskRewardRatio,
            String whyThisTrade,
            String riskNote) {
    }
}
