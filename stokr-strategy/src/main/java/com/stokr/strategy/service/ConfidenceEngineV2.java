package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.signals.StrategySignal;
import com.stokr.strategy.signals.SignalType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConfidenceEngineV2 {

    private static final String VERSION = "CONFIDENCE_V2";

    private final MarketDataQueryService marketDataQueryService;
    private final InstrumentNormalizationService instrumentNormalizationService;

    public ConfidenceEngineV2(
            MarketDataQueryService marketDataQueryService,
            InstrumentNormalizationService instrumentNormalizationService
    ) {
        this.marketDataQueryService = marketDataQueryService;
        this.instrumentNormalizationService = instrumentNormalizationService;
    }

    public StrategySignal enrich(StrategySignal signal, String strategyKey, String symbol, Instant asOf) {
        if (signal == null) {
            return null;
        }
        if (signal.type() == SignalType.HOLD || signal.type() == SignalType.EXIT) {
            return signal;
        }
        if (signal.confidenceScore() != null) {
            return signal;
        }

        String normalized = instrumentNormalizationService.normalizeForMarketData(symbol != null ? symbol : signal.symbol());
        List<MarketdataCandle> bars = normalized != null
                ? marketDataQueryService.lastBarsAscEndingAt(normalized, "1m", 30, asOf != null ? asOf : Instant.now())
                : List.of();

        ScorePart priceStructure = priceStructure(signal);
        ScorePart volumeExpansion = volumeExpansion(signal, bars);
        ScorePart oiConfirmation = neutral("OI confirmation unavailable", 20.0);
        ScorePart orderFlow = orderFlow(signal);
        ScorePart sectorStrength = sectorStrength(signal, strategyKey);
        ScorePart breadth = marketBreadth(signal);
        ScorePart liquidity = liquidityQuality(signal, bars);
        ScorePart volatility = volatilityAlignment(signal, bars);

        List<ScorePart> parts = List.of(
                priceStructure, volumeExpansion, oiConfirmation, orderFlow,
                sectorStrength, breadth, liquidity, volatility
        );
        double total = parts.stream().mapToDouble(ScorePart::points).sum();
        double bounded = Math.max(0d, Math.min(100d, total));
        BigDecimal score01 = BigDecimal.valueOf(bounded / 100d).setScale(6, RoundingMode.HALF_UP);
        BigDecimal probability = score01;
        String tradeQuality = qualityLabel(bounded);
        String breakdownJson = toBreakdownJson(parts, bounded);

        return signal.withConfidence(score01, probability, tradeQuality, VERSION, breakdownJson);
    }

    private ScorePart priceStructure(StrategySignal signal) {
        if (signal.entryPrice() == null || signal.stopPrice() == null || signal.targetPrice() == null) {
            return neutral("missing entry/stop/target", 25.0);
        }
        BigDecimal risk = signal.entryPrice().subtract(signal.stopPrice()).abs();
        BigDecimal reward = signal.targetPrice().subtract(signal.entryPrice()).abs();
        if (risk.signum() <= 0) {
            return new ScorePart("priceStructure", 0.0, 25.0, "invalid risk");
        }
        double rr = reward.divide(risk, 6, RoundingMode.HALF_UP).doubleValue();
        double ratio = Math.max(0.0, Math.min(1.0, (rr - 0.8) / 1.4));
        return weighted("priceStructure", ratio, 25.0, "rr=" + round(rr));
    }

    private ScorePart volumeExpansion(StrategySignal signal, List<MarketdataCandle> bars) {
        Double reasonVol = parseMetric(signal.reason(), "vol");
        if (reasonVol != null) {
            double ratio = Math.max(0.0, Math.min(1.0, reasonVol / 2.0));
            return weighted("volumeExpansion", ratio, 20.0, "reasonVol=" + round(reasonVol));
        }
        if (bars.size() >= 6) {
            double current = bars.get(bars.size() - 1).getVolume() != null ? bars.get(bars.size() - 1).getVolume().doubleValue() : 0d;
            double avg = 0d;
            int n = 0;
            for (int i = Math.max(0, bars.size() - 6); i < bars.size() - 1; i++) {
                if (bars.get(i).getVolume() != null) {
                    avg += bars.get(i).getVolume().doubleValue();
                    n++;
                }
            }
            if (n > 0 && avg > 0) {
                double volMult = current / (avg / n);
                double ratio = Math.max(0.0, Math.min(1.0, volMult / 2.0));
                return weighted("volumeExpansion", ratio, 20.0, "volMult=" + round(volMult));
            }
        }
        return neutral("volume unavailable", 20.0);
    }

    private ScorePart orderFlow(StrategySignal signal) {
        Double obi = parseMetric(signal.reason(), "obi");
        if (obi == null) {
            return neutral("obi unavailable", 10.0);
        }
        double ratio = Math.max(0.0, Math.min(1.0, Math.abs(obi)));
        return weighted("orderFlow", ratio, 10.0, "obi=" + round(obi));
    }

    private ScorePart sectorStrength(StrategySignal signal, String strategyKey) {
        Double sectorMove = parseMetric(signal.reason(), "sector_move");
        if (sectorMove != null) {
            double ratio = Math.max(0.0, Math.min(1.0, Math.abs(sectorMove) / 0.6));
            return weighted("sectorStrength", ratio, 10.0, "sectorMove=" + round(sectorMove));
        }
        if (strategyKey != null && strategyKey.toUpperCase(Locale.ROOT).contains("SECTOR")) {
            return neutral("sector move missing", 10.0);
        }
        return weighted("sectorStrength", 0.55, 10.0, "default");
    }

    private ScorePart marketBreadth(StrategySignal signal) {
        Double nifty = parseMetric(signal.reason(), "nifty");
        if (nifty == null) {
            return neutral("breadth unavailable", 5.0);
        }
        double ratio = 1.0 - Math.min(1.0, Math.abs(nifty) / 1.5);
        return weighted("marketBreadth", ratio, 5.0, "nifty=" + round(nifty));
    }

    private ScorePart liquidityQuality(StrategySignal signal, List<MarketdataCandle> bars) {
        if (bars.isEmpty()) {
            return neutral("bars unavailable", 5.0);
        }
        long nonZeroVolBars = bars.stream().filter(b -> b.getVolume() != null && b.getVolume().doubleValue() > 0d).count();
        double ratio = Math.max(0d, Math.min(1d, nonZeroVolBars / (double) bars.size()));
        return weighted("liquidityQuality", ratio, 5.0, "nonZeroVolBars=" + nonZeroVolBars);
    }

    private ScorePart volatilityAlignment(StrategySignal signal, List<MarketdataCandle> bars) {
        Double vix = parseMetric(signal.reason(), "vix");
        if (vix != null) {
            double ratio = Math.max(0.0, Math.min(1.0, (30.0 - Math.max(10.0, vix)) / 20.0));
            return weighted("volatilityAlignment", ratio, 5.0, "vix=" + round(vix));
        }
        if (bars.size() < 2) {
            return neutral("volatility unavailable", 5.0);
        }
        double avgRangePct = 0d;
        int n = 0;
        for (int i = Math.max(0, bars.size() - 8); i < bars.size(); i++) {
            MarketdataCandle c = bars.get(i);
            if (c.getHighPrice() == null || c.getLowPrice() == null || c.getClosePrice() == null || c.getClosePrice().signum() <= 0) {
                continue;
            }
            double rangePct = c.getHighPrice().subtract(c.getLowPrice())
                    .divide(c.getClosePrice(), 8, RoundingMode.HALF_UP).doubleValue() * 100d;
            avgRangePct += rangePct;
            n++;
        }
        if (n == 0) {
            return neutral("volatility unavailable", 5.0);
        }
        avgRangePct /= n;
        double ratio = 1.0 - Math.min(1.0, avgRangePct / 2.0);
        return weighted("volatilityAlignment", ratio, 5.0, "avgRangePct=" + round(avgRangePct));
    }

    private static ScorePart weighted(String key, double ratio, double weight, String detail) {
        return new ScorePart(key, Math.max(0.0, Math.min(weight, ratio * weight)), weight, detail);
    }

    private static ScorePart neutral(String detail, double weight) {
        return weighted("neutral", 0.5, weight, detail);
    }

    private static Double parseMetric(String reason, String key) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        Pattern p = Pattern.compile("(?i)" + Pattern.quote(key) + "\\s*=\\s*([+-]?\\d+(?:\\.\\d+)?)");
        Matcher m = p.matcher(reason);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String toBreakdownJson(List<ScorePart> parts, double total) {
        List<String> payload = new ArrayList<>();
        for (ScorePart p : parts) {
            if ("neutral".equals(p.key())) {
                continue;
            }
            payload.add(String.format(Locale.ROOT,
                    "{\"factor\":\"%s\",\"points\":%.2f,\"weight\":%.2f,\"detail\":\"%s\"}",
                    p.key(), p.points(), p.weight(), p.detail().replace("\"", "'")));
        }
        return String.format(Locale.ROOT, "{\"version\":\"%s\",\"score\":%.2f,\"factors\":[%s]}",
                VERSION, total, String.join(",", payload));
    }

    private static String qualityLabel(double score) {
        if (score >= 85) return "A+";
        if (score >= 75) return "A";
        if (score >= 65) return "B";
        if (score >= 55) return "C";
        return "D";
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private record ScorePart(String key, double points, double weight, String detail) {
    }
}
