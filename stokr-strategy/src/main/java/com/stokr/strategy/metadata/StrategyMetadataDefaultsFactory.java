package com.stokr.strategy.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.dto.metadata.StrategyDeploymentDefaultsDto;
import com.stokr.strategy.dto.metadata.StrategyExecutionCapabilitiesDto;
import com.stokr.strategy.dto.metadata.StrategyMetadataResponseDto;
import com.stokr.strategy.dto.metadata.StrategyParameterFieldDto;
import com.stokr.strategy.dto.metadata.StrategyPreviewMetricsDto;

import java.util.List;
import java.util.Locale;

/** Synthesizes launcher/backtest metadata when parameter_metadata_json is not yet published. */
public final class StrategyMetadataDefaultsFactory {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StrategyMetadataDefaultsFactory() {
    }

    public static StrategyMetadataResponseDto synthesize(StrategyDefinition def) {
        if (def == null || def.getStrategyKey() == null || def.getStrategyKey().isBlank()) {
            return null;
        }
        String key = def.getStrategyKey().trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "NSE_SPIKE_DETECTION" -> nseSpikeDetection(def);
            case "GAP_FILL", "VWAP_BOUNCE", "SECTOR_LAGGARD", "EARLY_BREAKOUT" -> intradayTemplate(def);
            default -> genericTemplate(def);
        };
    }

    private static StrategyMetadataResponseDto nseSpikeDetection(StrategyDefinition def) {
        return new StrategyMetadataResponseDto(
                2,
                def.getStrategyKey(),
                displayName(def, "NSE Spike Detection"),
                description(def, "1m momentum spike strategy for NSE equities using velocity, volume burst, and bar quality."),
                category(def, "INTRADAY"),
                List.of("NSE"),
                List.of("PRICE", "VOLUME"),
                new StrategyExecutionCapabilitiesDto(true, true, true),
                List.of(
                        numberParam("minCompositeScore", "Composite score threshold", 65.0, 50, 95, 1, "signals"),
                        numberParam("minVelocityPct", "Min velocity % / minute", 0.12, 0.05, 1.0, 2, "signals"),
                        numberParam("minVolumeMultiple", "Min volume burst multiple", 1.2, 1.0, 5.0, 2, "filters"),
                        numberParam("minBarQualityThreshold", "Min bar quality score", 60.0, 40, 90, 0, "filters"),
                        numberParam("maxWickPctBeforeReject", "Max wick % before reject", 0.70, 0.2, 0.95, 2, "filters"),
                        boolParam("requireContinuationCandle", "Require continuation candle", true, "signals"),
                        integerParam("cooldownSeconds", "Cooldown seconds", 300, 30, 3600, "timing"),
                        numberParam("slOffsetPct", "Stop-loss offset %", 0.50, 0.1, 2.0, 2, "risk"),
                        numberParam("targetTightRangeExtension", "Tight-range target extension %", 0.50, 0.1, 3.0, 2, "risk"),
                        numberParam("targetWideRangeExtension", "Wide-range target extension %", 1.50, 0.5, 5.0, 2, "risk")
                ),
                List.of("1m"),
                List.of("BACKTEST", "PAPER", "LIVE"),
                List.of("NONE", "PERCENT_2_BPS", "PERCENT_5_BPS"),
                List.of("NONE", "SPREAD_PROXY", "VOL_SCALED"),
                List.of("SIMULATED_DEFAULT", "REPLAY_RAW", "CONSERVATIVE", "BALANCED", "AGGRESSIVE"),
                new StrategyDeploymentDefaultsDto(
                        defaultSymbol(def, "RELIANCE"),
                        "1m",
                        "REPLAY_RAW",
                        "PERCENT_2_BPS",
                        "SPREAD_PROXY"
                ),
                new StrategyPreviewMetricsDto(2.8d, 58d, 8.5d, "High", 12d, "Intraday (session)")
        );
    }

    private static StrategyMetadataResponseDto intradayTemplate(StrategyDefinition def) {
        String tf = timeframe(def, "1m");
        List<String> allowedTf = "5m".equals(tf) ? List.of("5m", "15m") : List.of("1m", "5m");
        return new StrategyMetadataResponseDto(
                2,
                def.getStrategyKey(),
                displayName(def, def.getStrategyKey()),
                description(def, "Intraday strategy published by the Stokr catalog."),
                category(def, "INTRADAY"),
                List.of("NSE"),
                List.of("PRICE", "VOLUME"),
                new StrategyExecutionCapabilitiesDto(true, true, true),
                List.of(
                        numberParam("riskPct", "Risk % per trade", 0.5, 0.1, 2.0, 2, "risk"),
                        numberParam("stopLossPct", "Stop loss %", 0.35, 0.1, 3.0, 2, "risk"),
                        numberParam("takeProfitPct", "Take profit %", 0.55, 0.1, 5.0, 2, "risk"),
                        integerParam("cooldownBars", "Cooldown bars", 3, 0, 100, "timing")
                ),
                allowedTf,
                List.of("BACKTEST", "PAPER", "LIVE"),
                List.of("NONE", "PERCENT_2_BPS", "PERCENT_5_BPS"),
                List.of("NONE", "SPREAD_PROXY", "VOL_SCALED"),
                List.of("SIMULATED_DEFAULT", "REPLAY_RAW", "CONSERVATIVE", "BALANCED", "AGGRESSIVE"),
                new StrategyDeploymentDefaultsDto(
                        defaultSymbol(def, "RELIANCE"),
                        tf,
                        "REPLAY_RAW",
                        "PERCENT_2_BPS",
                        "SPREAD_PROXY"
                ),
                previewFor(def)
        );
    }

    private static StrategyMetadataResponseDto genericTemplate(StrategyDefinition def) {
        String tf = timeframe(def, "5m");
        return intradayTemplate(def);
    }

    private static StrategyPreviewMetricsDto previewFor(StrategyDefinition def) {
        String risk = def.getRiskLevel() != null ? def.getRiskLevel() : "Medium";
        double win = def.getWinRate() != null ? def.getWinRate().doubleValue() : 55d;
        double ret = def.getAvgMonthlyReturn() != null ? def.getAvgMonthlyReturn().doubleValue() : 1.5d;
        return new StrategyPreviewMetricsDto(ret, win, 10d, capitalize(risk), 4d, "Intraday");
    }

    private static StrategyParameterFieldDto numberParam(
            String id, String label, double defaultValue, double min, double max, int precision, String group
    ) {
        return new StrategyParameterFieldDto(
                id, "number", label, null, false, JSON.numberNode(defaultValue),
                MAPPER.createObjectNode().put("min", min).put("max", max).put("step", precision >= 2 ? 0.01 : 0.1),
                null, group, precision, null
        );
    }

    private static StrategyParameterFieldDto integerParam(
            String id, String label, int defaultValue, int min, int max, String group
    ) {
        return new StrategyParameterFieldDto(
                id, "integer", label, null, false, JSON.numberNode(defaultValue),
                MAPPER.createObjectNode().put("min", min).put("max", max),
                null, group, null, null
        );
    }

    private static StrategyParameterFieldDto boolParam(String id, String label, boolean defaultValue, String group) {
        return new StrategyParameterFieldDto(
                id, "boolean", label, null, false, JSON.booleanNode(defaultValue),
                null, null, group, null, null
        );
    }

    private static String defaultSymbol(StrategyDefinition def, String fallback) {
        if (def.getDefaultSymbols() != null && !def.getDefaultSymbols().isBlank()) {
            String first = def.getDefaultSymbols().split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return fallback;
    }

    private static String timeframe(StrategyDefinition def, String fallback) {
        if (def.getDefaultTimeframe() != null && !def.getDefaultTimeframe().isBlank()) {
            return def.getDefaultTimeframe().trim();
        }
        return fallback;
    }

    private static String displayName(StrategyDefinition def, String fallback) {
        return def.getDisplayName() != null && !def.getDisplayName().isBlank() ? def.getDisplayName().trim() : fallback;
    }

    private static String description(StrategyDefinition def, String fallback) {
        return def.getDescription() != null && !def.getDescription().isBlank() ? def.getDescription().trim() : fallback;
    }

    private static String category(StrategyDefinition def, String fallback) {
        return def.getCategory() != null && !def.getCategory().isBlank() ? def.getCategory().trim() : fallback;
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Medium";
        }
        String t = value.trim().toLowerCase(Locale.ROOT);
        return t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }
}
