package com.stokr.smartstrategy;

import com.stokr.arbitrage.IVRankService;
import com.stokr.arbitrage.ZerodhaSpotPriceFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MarketRegimeDetector {

    private final IVRankService ivRankService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private final ConcurrentHashMap<String, RegimeData> regimeCache = new ConcurrentHashMap<>();

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "FINNIFTY", "NSE:NIFTY FIN SERVICE",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT"
    );

    public enum Regime {
        TRENDING_UP, TRENDING_DOWN, SIDEWAYS, HIGH_VOLATILE
    }

    public record RegimeData(
        Regime regime,
        double spotPrice,
        double sma20,
        double dayRange,
        double avgDayRange,
        double ivRank,
        double atmIV,
        Map<String, Double> strategyWeights,
        long timestamp
    ) {}

    public MarketRegimeDetector(IVRankService ivRankService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.ivRankService = ivRankService;
        this.spotFetcher = spotFetcher;
    }

    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void refreshRegimes() {
        for (String underlying : SPOT_KEYS.keySet()) {
            try {
                RegimeData data = detectRegime(underlying);
                if (data != null) {
                    regimeCache.put(underlying, data);
                    log.debug("REGIME: {} = {} (IV rank: {}, day range: {}%)",
                        underlying, data.regime, String.format("%.0f", data.ivRank),
                        String.format("%.2f", data.dayRange));
                }
            } catch (Exception e) {
                log.debug("Regime detection failed for {}: {}", underlying, e.getMessage());
            }
        }
    }

    public RegimeData getRegime(String underlying) {
        RegimeData cached = regimeCache.get(underlying);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 600000) {
            return cached;
        }
        try {
            RegimeData fresh = detectRegime(underlying);
            if (fresh != null) regimeCache.put(underlying, fresh);
            return fresh;
        } catch (Exception e) {
            return cached;
        }
    }

    public Map<String, Object> getAllRegimes() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String underlying : SPOT_KEYS.keySet()) {
            RegimeData data = getRegime(underlying);
            if (data != null) {
                result.put(underlying, regimeToMap(data));
            }
        }
        return result;
    }

    public Map<String, Double> getStrategyWeights(String underlying) {
        RegimeData data = getRegime(underlying);
        return data != null ? data.strategyWeights : getDefaultWeights();
    }

    private RegimeData detectRegime(String underlying) {
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey, null);
        if (spotFut == null || spotFut[0] <= 0) return null;
        double spot = spotFut[0];

        Map<String, Object> ivSnapshot = ivRankService.computeIVSnapshot(underlying);
        double ivRank = 50;
        double atmIV = 15;
        if (ivSnapshot != null) {
            ivRank = ivSnapshot.get("ivRank") instanceof Number n ? n.doubleValue() : 50;
            atmIV = ivSnapshot.get("atmIV") instanceof Number n ? n.doubleValue() : 15;
        }

        double dayHigh = spot * 1.005;
        double dayLow = spot * 0.995;
        if (spotFut.length > 1 && spotFut[1] > 0) {
            double fut = spotFut[1];
            dayHigh = Math.max(spot, fut) * 1.002;
            dayLow = Math.min(spot, fut) * 0.998;
        }

        double dayRange = (dayHigh - dayLow) / spot * 100;
        double avgDayRange = underlying.equals("BANKNIFTY") ? 1.5 : 1.0;

        double sma20 = spot;
        double trendStrength = 0;
        if (spotFut.length > 1 && spotFut[1] > 0) {
            double fut = spotFut[1];
            trendStrength = (fut - spot) / spot * 100;
            sma20 = (spot + fut) / 2.0;
        }

        Regime regime;
        if (ivRank > 70 && dayRange > avgDayRange * 1.5) {
            regime = Regime.HIGH_VOLATILE;
        } else if (trendStrength > 0.15) {
            regime = Regime.TRENDING_UP;
        } else if (trendStrength < -0.15) {
            regime = Regime.TRENDING_DOWN;
        } else {
            regime = Regime.SIDEWAYS;
        }

        Map<String, Double> weights = computeStrategyWeights(regime, ivRank);

        return new RegimeData(regime, spot, sma20, dayRange, avgDayRange, ivRank, atmIV, weights, System.currentTimeMillis());
    }

    private Map<String, Double> computeStrategyWeights(Regime regime, double ivRank) {
        Map<String, Double> weights = new LinkedHashMap<>();

        switch (regime) {
            case TRENDING_UP -> {
                weights.put("JADE_LIZARD", 1.3);
                weights.put("RATIO_BUTTERFLY", 0.7);
                weights.put("IRON_CONDOR", 0.5);
                weights.put("BROKEN_WING_BUTTERFLY", 1.1);
                weights.put("SKEW_HARVEST", 1.0);
                weights.put("EXPIRY_THETA_CRUSH", 0.8);
                weights.put("BOX_SPREAD_ARB", 1.0);
                weights.put("CALENDAR_SPREAD_EDGE", 0.9);
            }
            case TRENDING_DOWN -> {
                weights.put("JADE_LIZARD", 0.6);
                weights.put("RATIO_BUTTERFLY", 0.8);
                weights.put("IRON_CONDOR", 0.5);
                weights.put("BROKEN_WING_BUTTERFLY", 1.2);
                weights.put("SKEW_HARVEST", 1.1);
                weights.put("EXPIRY_THETA_CRUSH", 0.9);
                weights.put("BOX_SPREAD_ARB", 1.0);
                weights.put("CALENDAR_SPREAD_EDGE", 0.8);
            }
            case SIDEWAYS -> {
                weights.put("IRON_CONDOR", 1.5);
                weights.put("RATIO_BUTTERFLY", 1.3);
                weights.put("JADE_LIZARD", 1.0);
                weights.put("BROKEN_WING_BUTTERFLY", 1.2);
                weights.put("SKEW_HARVEST", 0.9);
                weights.put("EXPIRY_THETA_CRUSH", 1.1);
                weights.put("BOX_SPREAD_ARB", 1.0);
                weights.put("CALENDAR_SPREAD_EDGE", 1.2);
            }
            case HIGH_VOLATILE -> {
                weights.put("IRON_CONDOR", 1.4);
                weights.put("BROKEN_WING_BUTTERFLY", 1.3);
                weights.put("SKEW_HARVEST", 1.2);
                weights.put("JADE_LIZARD", 1.1);
                weights.put("RATIO_BUTTERFLY", 0.8);
                weights.put("EXPIRY_THETA_CRUSH", 0.6);
                weights.put("BOX_SPREAD_ARB", 1.0);
                weights.put("CALENDAR_SPREAD_EDGE", 0.7);
            }
        }

        if (ivRank > 60) {
            weights.replaceAll((k, v) -> {
                if (k.equals("IRON_CONDOR") || k.equals("JADE_LIZARD") || k.equals("BROKEN_WING_BUTTERFLY")) {
                    return v * 1.1;
                }
                return v;
            });
        }

        return weights;
    }

    private Map<String, Double> getDefaultWeights() {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("IRON_CONDOR", 1.0);
        w.put("JADE_LIZARD", 1.0);
        w.put("BROKEN_WING_BUTTERFLY", 1.0);
        w.put("RATIO_BUTTERFLY", 1.0);
        w.put("SKEW_HARVEST", 1.0);
        w.put("EXPIRY_THETA_CRUSH", 1.0);
        w.put("BOX_SPREAD_ARB", 1.0);
        w.put("CALENDAR_SPREAD_EDGE", 1.0);
        return w;
    }

    private Map<String, Object> regimeToMap(RegimeData data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("regime", data.regime.name());
        map.put("spotPrice", data.spotPrice);
        map.put("sma20", Math.round(data.sma20 * 100.0) / 100.0);
        map.put("dayRange", Math.round(data.dayRange * 100.0) / 100.0);
        map.put("ivRank", data.ivRank);
        map.put("atmIV", data.atmIV);
        map.put("strategyWeights", data.strategyWeights);
        return map;
    }
}
