package com.stokr.arbitrage;

import com.stokr.marketdata.tick.AnomalyDetectionService;
import com.stokr.marketdata.tick.KiteTickWebSocketClient;
import com.stokr.marketdata.tick.TickData;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Dynamically classifies Indian market regimes (Chop vs Trend vs Shock) to determine
 * which strategy is safe to deploy, directly minimizing losses during runaway momentum.
 */
@Slf4j
@Service
public class MarketRegimeService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
        "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    private final ZerodhaSpotPriceFetcher spotPriceFetcher;
    private final AnomalyDetectionService anomalyService;
    private final KiteTickWebSocketClient tickClient;

    // Rolling 5-minute spot price samples (timestampMs -> price)
    private final Map<String, Deque<PriceSample>> rollingPrices = new ConcurrentHashMap<>();

    private record PriceSample(long timeMs, double price) {}

    public MarketRegimeService(ZerodhaSpotPriceFetcher spotPriceFetcher,
                               AnomalyDetectionService anomalyService,
                               KiteTickWebSocketClient tickClient) {
        this.spotPriceFetcher = spotPriceFetcher;
        this.anomalyService = anomalyService;
        this.tickClient = tickClient;
    }

    @PostConstruct
    public void init() {
        tickClient.addTickListener(this::onTick);
        log.info("MarketRegimeService initialized and listening for spot price ticks");
    }

    private void onTick(TickData tick) {
        if (tick == null || tick.getSymbol() == null || tick.getLtp() == null) return;
        String sym = tick.getSymbol();
        for (Map.Entry<String, String> entry : SPOT_KEYS.entrySet()) {
            if (entry.getValue().equals(sym) || sym.contains(entry.getKey())) {
                String underlying = entry.getKey();
                double price = tick.getLtp().doubleValue();
                if (price > 0) {
                    recordPrice(underlying, price);
                }
            }
        }
    }

    public synchronized void recordPrice(String underlying, double price) {
        long now = System.currentTimeMillis();
        Deque<PriceSample> deque = rollingPrices.computeIfAbsent(underlying, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(new PriceSample(now, price));
        // Prune older than 5 minutes (300,000 ms)
        while (!deque.isEmpty() && (now - deque.peekFirst().timeMs) > 300_000L) {
            deque.pollFirst();
        }
    }

    public double getFiveMinMovePct(String underlying, double currentPrice) {
        Deque<PriceSample> deque = rollingPrices.get(underlying);
        if (deque == null || deque.size() < 2) return 0.0;
        PriceSample oldest = deque.peekFirst();
        if (oldest == null || oldest.price <= 0) return 0.0;
        return ((currentPrice - oldest.price) / oldest.price) * 100.0;
    }

    public MarketRegime detectRegime(String underlying) {
        return detectRegime(underlying, LocalTime.now(IST));
    }

    public MarketRegime detectRegime(String underlying, LocalTime nowIST) {
        // Lock out during opening turbulence (< 09:30 AM) or post-market (> 15:25 PM)
        if (nowIST != null && (nowIST.isBefore(LocalTime.of(9, 30)) || nowIST.isAfter(LocalTime.of(15, 25)))) {
            return MarketRegime.VOLATILITY_SHOCK;
        }

        String spotKey = SPOT_KEYS.getOrDefault(underlying.toUpperCase(), "NSE:NIFTY 50");
        double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, "NFO:" + underlying);
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return MarketRegime.RANGE_BOUND_CHOP; // Safe fallback

        recordPrice(underlying, spot);

        // 1. Check VWAP Deviation
        BigDecimal vwapBd = anomalyService.getVwap(spotKey);
        double vwap = (vwapBd != null && vwapBd.compareTo(BigDecimal.ZERO) > 0) ? vwapBd.doubleValue() : 0.0;
        double devPct = (vwap > 0) ? ((spot - vwap) / vwap) * 100.0 : 0.0;

        // 2. Check 5-minute rolling momentum
        double fiveMinMovePct = getFiveMinMovePct(underlying, spot);

        // Volatility shock check: Sudden rapid displacement > 0.7% in 5 min or > 1.2% VWAP deviation
        if (Math.abs(fiveMinMovePct) >= 0.70 || Math.abs(devPct) >= 1.20) {
            log.info("REGIME_DETECT: {} is in VOLATILITY_SHOCK (devPct={:.2f}%, 5mMove={:.2f}%)",
                underlying, devPct, fiveMinMovePct);
            return MarketRegime.VOLATILITY_SHOCK;
        }

        // Bullish Trend: Consistently above VWAP (+0.30%) and expanding
        if (devPct > 0.30 && fiveMinMovePct >= 0.05) {
            log.info("REGIME_DETECT: {} is in BULLISH_TREND (devPct={:.2f}%, 5mMove={:.2f}%)",
                underlying, devPct, fiveMinMovePct);
            return MarketRegime.BULLISH_TREND;
        }

        // Bearish Trend: Consistently below VWAP (-0.30%) and deteriorating
        if (devPct < -0.30 && fiveMinMovePct <= -0.05) {
            log.info("REGIME_DETECT: {} is in BEARISH_TREND (devPct={:.2f}%, 5mMove={:.2f}%)",
                underlying, devPct, fiveMinMovePct);
            return MarketRegime.BEARISH_TREND;
        }

        return MarketRegime.RANGE_BOUND_CHOP;
    }

    /**
     * Determines whether a specific strategy type is mathematically safe to deploy
     * in the current market environment.
     */
    public boolean isStrategyAllowed(String strategyType, String underlying) {
        return isStrategyAllowed(strategyType, underlying, LocalTime.now(IST));
    }

    public boolean isStrategyAllowed(String strategyType, String underlying, LocalTime nowIST) {
        MarketRegime regime = detectRegime(underlying, nowIST);
        String strat = (strategyType != null) ? strategyType.toUpperCase() : "UNKNOWN";

        // Arbitrage is delta-neutral and risk-free across all regimes except extreme market closure
        if (strat.contains("BOX") || strat.contains("PARITY")) {
            return regime != MarketRegime.VOLATILITY_SHOCK;
        }

        switch (regime) {
            case VOLATILITY_SHOCK:
                log.warn("REGIME_BLOCKED: All new {} entries blocked for {} due to VOLATILITY_SHOCK", strat, underlying);
                return false;

            case BULLISH_TREND:
                if (strat.contains("CONDOR") || strat.contains("BUTTERFLY")) {
                    log.info("REGIME_BLOCKED: {} blocked for {} during BULLISH_TREND (prevents wing blowouts)", strat, underlying);
                    return false;
                }
                return true;

            case BEARISH_TREND:
                if (strat.contains("CONDOR") || strat.contains("BUTTERFLY")) {
                    log.info("REGIME_BLOCKED: {} blocked for {} during BEARISH_TREND (prevents wing blowouts)", strat, underlying);
                    return false;
                }
                return true;

            case RANGE_BOUND_CHOP:
            default:
                // Range-bound chop is ideal for Iron Condors and theta neutral spreads
                return true;
        }
    }
}