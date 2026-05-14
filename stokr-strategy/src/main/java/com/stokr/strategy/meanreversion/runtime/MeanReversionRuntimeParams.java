package com.stokr.strategy.meanreversion.runtime;

import com.stokr.strategy.meanreversion.MeanReversionParams;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Strategy-only runtime parameters for mean-reversion range fade (metadata v2 / PR-3).
 * Parsed from persisted {@code strategyParameters}; merged with {@link MeanReversionParams} for RSI/catalog defaults.
 */
public record MeanReversionRuntimeParams(
        BigDecimal entryMaxRangeWidthPct,
        BigDecimal exitThreshold,
        String stopLossType,
        BigDecimal stopLossPercent,
        BigDecimal takeProfitPercent,
        AtrFilterMode atrFilter,
        VolumeFilterMode volumeFilter,
        int cooldownCandles,
        BigDecimal spreadToleranceBps,
        int maxConcurrentPositions,
        int confirmationCandles,
        SessionFilterMode sessionFilter,
        int maxHoldingCandles,
        VolatilityFilterMode volatilityFilter,
        ExecutionAggressionMode executionAggression,
        boolean partialExitEnabled,
        boolean trailingStopEnabled,
        BigDecimal rsiBuyMax,
        BigDecimal rsiSellMin,
        BigDecimal confidenceBase
) {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    /**
     * Legacy-equivalent defaults: no volume gate, no cooldown, wide session, no volatility widening.
     */
    public static MeanReversionRuntimeParams fromVariant(MeanReversionParams v) {
        return new MeanReversionRuntimeParams(
                v.maxRangeWidthPct(),
                new BigDecimal("0.2"),
                "FIXED_PCT",
                new BigDecimal("0.35"),
                new BigDecimal("0.55"),
                AtrFilterMode.STANDARD,
                VolumeFilterMode.OFF,
                0,
                BigDecimal.ZERO,
                99,
                0,
                SessionFilterMode.ALL,
                Integer.MAX_VALUE,
                VolatilityFilterMode.OFF,
                ExecutionAggressionMode.BALANCED,
                false,
                false,
                v.rsiBuyMax(),
                v.rsiSellMin(),
                v.confidenceScore()
        );
    }

    public static MeanReversionRuntimeParams merge(Map<String, Object> map, MeanReversionParams variant) {
        if (map == null || map.isEmpty()) {
            return fromVariant(variant);
        }
        MeanReversionRuntimeParams d = fromVariant(variant);
        return new MeanReversionRuntimeParams(
                bd(map.get("entryThreshold"), d.entryMaxRangeWidthPct()),
                bd(map.get("exitThreshold"), d.exitThreshold()),
                str(map.get("stopLossType"), d.stopLossType()),
                bd(map.get("stopLossPercent"), d.stopLossPercent()),
                bd(map.get("takeProfitPercent"), d.takeProfitPercent()),
                enumVal(map.get("atrFilter"), AtrFilterMode.class, d.atrFilter()),
                enumVal(map.get("volumeFilter"), VolumeFilterMode.class, d.volumeFilter()),
                iv(map.get("cooldownCandles"), d.cooldownCandles()),
                bd(map.get("spreadTolerance"), d.spreadToleranceBps()),
                iv(map.get("maxConcurrentPositions"), d.maxConcurrentPositions()),
                iv(map.get("confirmationCandles"), d.confirmationCandles()),
                enumVal(map.get("sessionFilter"), SessionFilterMode.class, d.sessionFilter()),
                iv(map.get("maxHoldingCandles"), d.maxHoldingCandles()),
                enumVal(map.get("volatilityFilter"), VolatilityFilterMode.class, d.volatilityFilter()),
                enumVal(map.get("executionAggression"), ExecutionAggressionMode.class, d.executionAggression()),
                bv(map.get("partialExitEnabled"), d.partialExitEnabled()),
                bv(map.get("trailingStopEnabled"), d.trailingStopEnabled()),
                d.rsiBuyMax(),
                d.rsiSellMin(),
                d.confidenceBase()
        );
    }

    public BigDecimal volatilityWidthMultiplier() {
        return switch (volatilityFilter) {
            case OFF, LOW -> BigDecimal.ONE;
            case MEDIUM -> new BigDecimal("1.08");
            case HIGH -> new BigDecimal("1.15");
        };
    }

    public BigDecimal effectiveEntryWidthCap(MeanReversionParams variant) {
        BigDecimal base = entryMaxRangeWidthPct != null ? entryMaxRangeWidthPct : variant.maxRangeWidthPct();
        return base.multiply(volatilityWidthMultiplier(), MC);
    }

    public BigDecimal atrCompressionCutoff() {
        return switch (atrFilter) {
            case OFF -> new BigDecimal("0.012");
            case STANDARD -> new BigDecimal("0.004");
            case STRICT -> new BigDecimal("0.0025");
        };
    }

    public BigDecimal volumeRelaxedFloor() {
        return new BigDecimal("0.7");
    }

    public BigDecimal volumeStrictFloor() {
        return new BigDecimal("1.2");
    }

    public BigDecimal scaledConfidence() {
        BigDecimal m = switch (executionAggression) {
            case CONSERVATIVE -> new BigDecimal("0.92");
            case BALANCED -> BigDecimal.ONE;
            case AGGRESSIVE -> new BigDecimal("1.08");
        };
        return confidenceBase.multiply(m, MC).min(BigDecimal.ONE);
    }

    public BigDecimal rrRiskMultiplier() {
        return new BigDecimal("1.5").add(exitThreshold.multiply(new BigDecimal("0.25"), MC), MC);
    }

    public String toExecutionSnapshotJson(MeanReversionParams variant) {
        return "{\"catalogStrategyKey\":\"" + variant.catalogStrategyKey()
                + "\",\"strategyVersion\":\"" + variant.strategyVersion()
                + "\",\"entryMaxRangeWidthPct\":\"" + entryMaxRangeWidthPct.toPlainString()
                + "\",\"exitThreshold\":\"" + exitThreshold.toPlainString()
                + "\",\"atrFilter\":\"" + atrFilter.name()
                + "\",\"volumeFilter\":\"" + volumeFilter.name()
                + "\",\"cooldownCandles\":" + cooldownCandles
                + ",\"spreadToleranceBps\":\"" + spreadToleranceBps.toPlainString()
                + "\",\"maxConcurrentPositions\":" + maxConcurrentPositions
                + ",\"confirmationCandles\":" + confirmationCandles
                + ",\"sessionFilter\":\"" + sessionFilter.name()
                + "\",\"maxHoldingCandles\":" + maxHoldingCandles
                + ",\"volatilityFilter\":\"" + volatilityFilter.name()
                + "\",\"executionAggression\":\"" + executionAggression.name()
                + "\",\"partialExitEnabled\":" + partialExitEnabled
                + ",\"trailingStopEnabled\":" + trailingStopEnabled
                + ",\"rsiBuyMax\":\"" + rsiBuyMax.toPlainString()
                + "\",\"rsiSellMin\":\"" + rsiSellMin.toPlainString()
                + "\"}";
    }

    private static BigDecimal bd(Object o, BigDecimal dflt) {
        if (o == null) {
            return dflt;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (o instanceof String s && !s.isBlank()) {
            return new BigDecimal(s.trim());
        }
        return dflt;
    }

    private static int iv(Object o, int dflt) {
        if (o == null) {
            return dflt;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            return Integer.parseInt(s.trim());
        }
        return dflt;
    }

    private static boolean bv(Object o, boolean dflt) {
        if (o == null) {
            return dflt;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return dflt;
    }

    private static String str(Object o, String dflt) {
        if (o == null) {
            return dflt;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? dflt : s;
    }

    private static <E extends Enum<E>> E enumVal(Object raw, Class<E> type, E dflt) {
        if (raw == null) {
            return dflt;
        }
        String s = raw.toString().trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            return dflt;
        }
        try {
            return Enum.valueOf(type, s);
        } catch (IllegalArgumentException ex) {
            return dflt;
        }
    }
}
