package com.stokr.intraday.controller;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.intraday.stream.RealTimeSetupStream;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-first intelligence snapshot for the ADV Dashboard (confidence-driven, not indicator noise).
 */
@RestController
@RequestMapping("/api/v1/adv-dashboard")
@RequiredArgsConstructor
public class AdvIntelligenceDashboardController {

    private final RealTimeSetupStream realTimeStream;

    @GetMapping("/snapshot")
    public AdvDashboardSnapshot snapshot() {
        MarketRegimeDetector.MarketRegime regime = realTimeStream.getCurrentRegime();
        List<CurrentSetup> board = realTimeStream.getRankingBoard();
        RealTimeSetupStream.StreamStatistics stats = realTimeStream.getStatistics();

        List<SetupCardDto> setups = board.stream()
                .map(this::toSetupCard)
                .toList();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("stocksTracked", stats.tickCount);
        metrics.put("activeSetups", stats.rankingBoardSize);
        metrics.put("topScore", stats.topSetupScore);
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
        if (setup.getMarketRegime() != null) {
            badges.add("REGIME " + setup.getMarketRegime());
        }

        String why = buildWhyText(setup, tier);
        String risk = buildRiskText(setup);

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
                why,
                risk
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
            sb.append(setup.getSetupType().replace('_', ' ')).append(" structure detected. ");
        }
        if (setup.getAdjustedProbability() != null) {
            sb.append("Adjusted probability ")
                    .append(setup.getAdjustedProbability().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP))
                    .append("%. ");
        }
        if (setup.getRegimeAdjustment() != null && setup.getRegimeAdjustment().signum() != 0) {
            sb.append("Regime adjustment ").append(setup.getRegimeAdjustment()).append("%. ");
        }
        if (setup.getRiskRewardRatio() != null) {
            sb.append("R:R ").append(setup.getRiskRewardRatio()).append(".");
        }
        return sb.toString().trim();
    }

    private static String buildRiskText(CurrentSetup setup) {
        if (setup.getStopLoss() != null) {
            return "Invalidation below " + setup.getStopLoss() + "; respect VWAP/structure loss if momentum fades.";
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
