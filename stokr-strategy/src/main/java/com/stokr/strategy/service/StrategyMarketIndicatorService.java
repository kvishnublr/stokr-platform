package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized market indicator service for all strategy generators.
 *
 * Provides real-time (or best-effort approximation) of:
 *   1. VIX — from INDIA VIX candle data or configurable default
 *   2. PCR — put-call ratio approximated from pressure tracker or configurable default
 *   3. Lead-Lag — delegated to BankLeadLagService
 *   4. Cross-Index — NIFTY vs BANKNIFTY 5-minute change comparison
 *   5. Nifty day % — session open to current price for NIFTY 50
 *
 * All results cached for 30s to avoid DB hammering during 5s scan cycles.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyMarketIndicatorService {

    private static final long CACHE_TTL_MS = 30_000;
    private static final String VIX_SYMBOL = "INDIA VIX";
    private static final String NIFTY_SYMBOL = "NIFTY 50";
    private static final String BANKNIFTY_SYMBOL = "BANKNIFTY";

    private final MarketDataQueryService marketDataQueryService;
    private final OrderBookPressureTracker pressureTracker;
    private final BankLeadLagService bankLeadLagService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private String zoneStr;

    @Value("${stokr.strategy.default-vix:17.5}")
    private double defaultVix;

    @Value("${stokr.strategy.default-pcr:1.05}")
    private double defaultPcr;

    private final ConcurrentHashMap<String, CachedValue<Double>> doubleCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedValue<CrossIndexResult>> crossCache = new ConcurrentHashMap<>();

    // ═══ 1. VIX ═══

    public double getVix(Instant asOf) {
        Instant now = asOf != null ? asOf : Instant.now();
        CachedValue<Double> cached = doubleCache.get("vix");
        if (cached != null && Duration.between(cached.timestamp, now).toMillis() < CACHE_TTL_MS) {
            return cached.value;
        }

        double vix = defaultVix;
        try {
            List<MarketdataCandle> vixBars = marketDataQueryService.lastBarsAsc(VIX_SYMBOL, "1m", 5);
            if (!vixBars.isEmpty()) {
                BigDecimal close = vixBars.get(vixBars.size() - 1).getClosePrice();
                if (close != null && close.compareTo(BigDecimal.ZERO) > 0) {
                    vix = close.doubleValue();
                }
            }
        } catch (Exception e) {
            log.trace("market_indicator.vix_fallback reason={}", e.getMessage());
        }

        if (vix == defaultVix) {
            PressureSnapshot vixSnap = pressureTracker.getSnapshot(VIX_SYMBOL);
            if (vixSnap != null && vixSnap.price() > 0) {
                vix = vixSnap.price();
            }
        }

        doubleCache.put("vix", new CachedValue<>(now, vix));
        return vix;
    }

    // ═══ 2. PCR ═══

    public double getPcr(Instant asOf) {
        Instant now = asOf != null ? asOf : Instant.now();
        CachedValue<Double> cached = doubleCache.get("pcr");
        if (cached != null && Duration.between(cached.timestamp, now).toMillis() < CACHE_TTL_MS) {
            return cached.value;
        }

        double pcr = defaultPcr;
        try {
            PressureSnapshot niftySnap = pressureTracker.getSnapshot("NIFTY 50");
            if (niftySnap != null) {
                double sellPressure = 1.0 - niftySnap.imbalanceRatio();
                pcr = 0.7 + sellPressure * 0.9;
                pcr = Math.round(pcr * 100.0) / 100.0;
            }
        } catch (Exception e) {
            log.trace("market_indicator.pcr_fallback reason={}", e.getMessage());
        }

        doubleCache.put("pcr", new CachedValue<>(now, pcr));
        return pcr;
    }

    // ═══ 3. Lead-Lag ═══

    public BankLeadLagService.LeadLagResult getLeadLag(Instant asOf) {
        return bankLeadLagService.compute(asOf);
    }

    // ═══ 4. Cross-Index ═══

    public CrossIndexResult getCrossIndex(String forIndex, Instant asOf) {
        Instant now = asOf != null ? asOf : Instant.now();
        String cacheKey = "cross_" + forIndex;
        CachedValue<CrossIndexResult> cached = crossCache.get(cacheKey);
        if (cached != null && Duration.between(cached.timestamp, now).toMillis() < CACHE_TTL_MS) {
            return cached.value;
        }

        String otherSymbol = "NIFTY".equalsIgnoreCase(forIndex) ? BANKNIFTY_SYMBOL : NIFTY_SYMBOL;
        CrossIndexResult result;
        try {
            List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(otherSymbol, "1m", 10);
            if (bars.size() >= 6) {
                int n = bars.size();
                BigDecimal current = bars.get(n - 1).getClosePrice();
                BigDecimal fiveAgo = bars.get(Math.max(0, n - 6)).getClosePrice();
                if (current != null && fiveAgo != null && fiveAgo.compareTo(BigDecimal.ZERO) > 0) {
                    double changePct = current.subtract(fiveAgo)
                        .divide(fiveAgo, 6, RoundingMode.HALF_UP)
                        .doubleValue() * 100.0;
                    result = new CrossIndexResult(changePct, otherSymbol, true);
                } else {
                    result = new CrossIndexResult(0.0, otherSymbol, false);
                }
            } else {
                result = new CrossIndexResult(0.0, otherSymbol, false);
            }
        } catch (Exception e) {
            result = new CrossIndexResult(0.0, otherSymbol, false);
        }

        crossCache.put(cacheKey, new CachedValue<>(now, result));
        return result;
    }

    // ═══ 5. Nifty Day % ═══

    public Double getNiftyDayPct(Instant asOf) {
        Instant now = asOf != null ? asOf : Instant.now();
        CachedValue<Double> cached = doubleCache.get("nifty_day_pct");
        if (cached != null && Duration.between(cached.timestamp, now).toMillis() < CACHE_TTL_MS) {
            return cached.value;
        }

        Double pct = null;
        try {
            List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(NIFTY_SYMBOL, "1m", 60);
            if (bars.size() >= 2) {
                BigDecimal sessionOpen = bars.get(0).getOpenPrice();
                BigDecimal current = bars.get(bars.size() - 1).getClosePrice();
                if (sessionOpen != null && current != null && sessionOpen.compareTo(BigDecimal.ZERO) > 0) {
                    pct = current.subtract(sessionOpen)
                        .divide(sessionOpen, 6, RoundingMode.HALF_UP)
                        .doubleValue() * 100.0;
                    pct = Math.round(pct * 10000.0) / 10000.0;
                }
            }
        } catch (Exception e) {
            log.trace("market_indicator.nifty_day_pct_fallback reason={}", e.getMessage());
        }

        if (pct != null) {
            doubleCache.put("nifty_day_pct", new CachedValue<>(now, pct));
        }
        return pct;
    }

    // ═══ Snapshot ═══

    public MarketSnapshot getSnapshot(Instant asOf) {
        return new MarketSnapshot(getVix(asOf), getPcr(asOf), getLeadLag(asOf), getNiftyDayPct(asOf));
    }

    // ═══ Records ═══

    public record MarketSnapshot(double vix, double pcr, BankLeadLagService.LeadLagResult leadLag, Double niftyDayPct) {}
    public record CrossIndexResult(double changePct, String otherSymbol, boolean available) {}
    private record CachedValue<T>(Instant timestamp, T value) {}
}
