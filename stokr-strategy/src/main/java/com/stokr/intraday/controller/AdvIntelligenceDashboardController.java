package com.stokr.intraday.controller;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.intraday.service.AdvIntelligenceFeedService;
import com.stokr.intraday.service.AdvIntelligenceTerminalService;
import com.stokr.intraday.stream.RealTimeSetupStream;
import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.auth.security.StokrUserDetails;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/snapshot")
    @PreAuthorize("isAuthenticated()")
    public AdvDashboardSnapshot snapshot() {
        if (realTimeStream.getStatistics().tickCount == 0) {
            feedService.refreshNow();
        }
        MarketRegimeDetector.MarketRegime regime = realTimeStream.getCurrentRegime();
        List<CurrentSetup> board = realTimeStream.getRankingBoard();
        RealTimeSetupStream.StreamStatistics stats = realTimeStream.getStatistics();

        List<SetupCardDto> setups = board.stream().map(this::toSetupCard).toList();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("stocksTracked", Math.max(stats.tickCount, setups.size()));
        metrics.put("activeSetups", Math.max(stats.rankingBoardSize, setups.size()));
        metrics.put("topScore", stats.topSetupScore != null && stats.topSetupScore.signum() > 0
                ? stats.topSetupScore.intValue() : topFromSetups(setups));
        metrics.put("regime", regime.name());

        return new AdvDashboardSnapshot(
                regime.name(),
                regimeDescription(regime),
                metrics,
                setups,
                List.of(
                        "Price structure and volume expansion drive ranking — not raw indicators.",
                        "Only top-quality setups surface; weak signals are filtered.",
                        "Regime context adjusts confidence before any trade suggestion."
                )
        );
    }

    @GetMapping("/terminal")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> terminal(@AuthenticationPrincipal StokrUserDetails user) {
        var uid = user != null ? user.getId() : null;
        return ApiResponse.ok(terminalService.buildTerminal(uid), CorrelationIdHolder.get());
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
