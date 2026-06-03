package com.stokr.intraday.service;

import com.stokr.strategy.lifecycle.StrategyLifecycleProfile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Enriches ADV scanner rows with actionable entry/exit guidance for the enhanced dashboard.
 * Display-only — does not alter OMS or {@link com.stokr.strategy.service.PressureSmartExitService}.
 */
@Component
public class AdvTradePlanEnricher {

    public void enrich(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return;
        }
        BigDecimal ltp = toDecimal(row.get("ltp"));
        if (ltp == null) {
            ltp = toDecimal(row.get("entryPrice"));
        }
        String side = normalizeSide(String.valueOf(row.getOrDefault("side", "BUY")));
        String setupKey = normalizeSetupKey(
                String.valueOf(row.getOrDefault("setupType", row.getOrDefault("strategy", ""))));

        BigDecimal entry = toDecimal(row.get("entryPrice"));
        BigDecimal stop = toDecimal(row.get("stopLoss"));
        BigDecimal target = toDecimal(row.get("targetPrice"));

        if (entry == null && ltp != null) {
            entry = ltp;
        }
        if (entry != null && (stop == null || target == null)) {
            PlanDefaults defaults = planDefaults(setupKey, side);
            if (stop == null) {
                stop = scalePrice(entry, side.equals("BUY") ? defaults.stopPctDown() : defaults.stopPctUp());
            }
            if (target == null) {
                target = scalePrice(entry, side.equals("BUY") ? defaults.targetPctUp() : defaults.targetPctDown());
            }
        }

        if (entry != null) {
            row.put("entryPrice", entry);
            BigDecimal zonePad = entry.multiply(BigDecimal.valueOf(0.0015)).setScale(2, RoundingMode.HALF_UP);
            if (side.equals("BUY")) {
                row.put("entryZoneLow", entry.subtract(zonePad).setScale(2, RoundingMode.HALF_UP));
                row.put("entryZoneHigh", entry.add(zonePad.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)));
            } else {
                row.put("entryZoneLow", entry.subtract(zonePad.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)));
                row.put("entryZoneHigh", entry.add(zonePad).setScale(2, RoundingMode.HALF_UP));
            }
        }
        if (stop != null) {
            row.put("stopLoss", stop);
        }
        if (target != null) {
            row.put("targetPrice", target);
        }

        row.put("entryTrigger", buildEntryTrigger(setupKey, side, row));
        row.put("invalidation", buildInvalidation(setupKey, side, stop));
        row.put("exitPlan", buildExitPlan(setupKey, side, row));
        row.put("tradeCall", buildTradeCall(side, row));
        row.put("planSource", row.containsKey("stopLoss") && row.get("stopLoss") != null ? "SIGNAL" : "SETUP_MODEL");
        row.put("intelligenceActionable", isIntelligenceActionable(row));
    }

    private static boolean isIntelligenceActionable(Map<String, Object> row) {
        String st = String.valueOf(row.getOrDefault("executionStatus", ""));
        return "INTELLIGENCE_ONLY".equals(st) || "WATCHLIST".equals(st) || "EXECUTABLE".equals(st);
    }

    private static String buildTradeCall(String side, Map<String, Object> row) {
        String symbol = String.valueOf(row.getOrDefault("symbol", ""));
        Object zoneLow = row.get("entryZoneLow");
        Object zoneHigh = row.get("entryZoneHigh");
        String zone = zoneLow != null && zoneHigh != null
                ? zoneLow + " – " + zoneHigh
                : String.valueOf(row.getOrDefault("entryPrice", "LTP"));
        if ("SELL".equals(side)) {
            return "SELL " + symbol + " into " + zone + " on weakness / failed bounce";
        }
        return "BUY " + symbol + " on " + zone + " with trigger confirmation";
    }

    private static String buildEntryTrigger(String setupKey, String side, Map<String, Object> row) {
        return switch (setupKey) {
            case "GAP_FILL" -> side.equals("BUY")
                    ? "Enter on hold above gap midpoint with volume ≥ prior bar"
                    : "Short rejection at gap fill resistance with rising sell pressure";
            case "HIGH_MOMENTUM" -> side.equals("BUY")
                    ? "Break + hold above session VWAP with buyPct ≥ 55"
                    : "Fade extended move: sell below prior 5m low after momentum stall";
            case "VOLUME_EXPANSION" -> "Volume spike + price acceptance; enter on retest of expansion candle body";
            case "VWAP_BOUNCE", "S3_VWAP_RETEST" -> side.equals("BUY")
                    ? "Touch VWAP + bullish rejection wick; enter above rejection high"
                    : "VWAP reject + close below; short into next liquidity pocket";
            case "EARLY_BREAKOUT", "EARLY_BREAKOUT_DETECTOR" -> "Opening range break with volume confirmation (9:20–10:30 IST)";
            case "SECTOR_LAGGARD" -> "Relative strength vs sector leader; enter when laggard catches bid";
            default -> side.equals("BUY")
                    ? "AI rank + order-flow alignment; confirm with 1m close above entry zone"
                    : "AI rank + distribution; confirm with 1m close below entry zone";
        };
    }

    private static String buildInvalidation(String setupKey, String side, BigDecimal stop) {
        String stopTxt = stop != null ? " @ " + stop : "";
        return switch (setupKey) {
            case "GAP_FILL" -> side.equals("BUY")
                    ? "Gap re-opens / close below gap floor" + stopTxt
                    : "Gap fills completely against short" + stopTxt;
            case "HIGH_MOMENTUM" -> "Momentum flips: buyPct < 45 or 1m counter-trend body > 55%" + stopTxt;
            case "VOLUME_EXPANSION" -> "Expansion candle fully retraced on low volume" + stopTxt;
            case "VWAP_BOUNCE", "S3_VWAP_RETEST" -> "Full VWAP cross against position" + stopTxt;
            default -> side.equals("BUY")
                    ? "Structure break below entry zone / hard stop" + stopTxt
                    : "Squeeze above entry zone / hard stop" + stopTxt;
        };
    }

    private static String buildExitPlan(String setupKey, String side, Map<String, Object> row) {
        String strategy = String.valueOf(row.getOrDefault("strategy", setupKey));
        StrategyLifecycleProfile profile = StrategyLifecycleProfile.forStrategy(
                strategy.equals("LIVE_MARKET") || strategy.equals("SETUP_DETECT") ? setupKey : strategy);

        String targetHint = row.get("targetPrice") != null
                ? "Target " + row.get("targetPrice")
                : "partial @ 1R";
        String base = switch (setupKey) {
            case "GAP_FILL" -> "Scale 50% at gap fill; trail remainder with 1m structure; time-stop "
                    + profile.timeStopMinutes() + "m; regime CHOPPY → exit early";
            case "HIGH_MOMENTUM" -> targetHint + "; trail after 40% progress; exit on imbalance collapse (not generic pressure until min-hold "
                    + profile.minHoldSeconds() + "s)";
            case "VOLUME_EXPANSION" -> targetHint + "; exit if volume < 35% of expansion bar; trail breakeven after MFE 40%";
            case "VWAP_BOUNCE", "S3_VWAP_RETEST" -> targetHint + "; exit full at VWAP extension or VWAP recross; time-stop "
                    + profile.timeStopMinutes() + "m";
            case "EARLY_BREAKOUT" -> targetHint + "; session time-stop " + profile.timeStopMinutes()
                    + "m; exit if opening range reclaimed";
            case "SECTOR_LAGGARD" -> targetHint + "; exit if sector RS reverses; partial @ 1R then trail";
            default -> targetHint + "; smart exit: hard SL → liquidity → pressure reversal after min-hold "
                    + profile.minHoldSeconds() + "s; regime change downgrades score";
        };
        if ("INTELLIGENCE_ONLY".equals(String.valueOf(row.get("executionStatus")))) {
            return base + " · Plan only — not sent to OMS";
        }
        if ("EXECUTED".equals(String.valueOf(row.get("executionStatus")))) {
            return base + " · Live position — OMS lifecycle active";
        }
        return base;
    }

    private static PlanDefaults planDefaults(String setupKey, String side) {
        return switch (setupKey) {
            case "GAP_FILL" -> new PlanDefaults(-0.012, 0.025, -0.025, 0.012);
            case "HIGH_MOMENTUM" -> new PlanDefaults(-0.008, 0.018, -0.018, 0.008);
            case "VOLUME_EXPANSION" -> new PlanDefaults(-0.010, 0.022, -0.022, 0.010);
            case "VWAP_BOUNCE", "S3_VWAP_RETEST" -> new PlanDefaults(-0.009, 0.020, -0.020, 0.009);
            case "EARLY_BREAKOUT" -> new PlanDefaults(-0.010, 0.023, -0.023, 0.010);
            case "SECTOR_LAGGARD" -> new PlanDefaults(-0.011, 0.022, -0.022, 0.011);
            default -> new PlanDefaults(-0.010, 0.020, -0.020, 0.010);
        };
    }

    private static BigDecimal scalePrice(BigDecimal base, double pct) {
        return base.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(pct)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.replace(",", "").trim()).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String normalizeSide(String side) {
        String s = side.toUpperCase(Locale.ROOT);
        if (s.contains("SELL") || s.contains("SHORT")) {
            return "SELL";
        }
        return "BUY";
    }

    static String normalizeSetupKey(String raw) {
        if (raw == null || raw.isBlank() || "—".equals(raw)) {
            return "ACTIVE";
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (key) {
            case "GAP_FILL", "GAPFILL" -> "GAP_FILL";
            case "VWAP_BOUNCE", "VWAPBOUNCE" -> "VWAP_BOUNCE";
            case "SECTOR_LAGGARD", "SECTORLAGGARD" -> "SECTOR_LAGGARD";
            case "EARLY_BREAKOUT", "EARLYBREAKOUT" -> "EARLY_BREAKOUT";
            case "HIGH_MOMENTUM", "HIGHMOMENTUM" -> "HIGH_MOMENTUM";
            case "VOLUME_EXPANSION", "VOLUMEEXPANSION" -> "VOLUME_EXPANSION";
            default -> key;
        };
    }

    private record PlanDefaults(double stopPctDown, double targetPctUp, double targetPctDown, double stopPctUp) {}
}
