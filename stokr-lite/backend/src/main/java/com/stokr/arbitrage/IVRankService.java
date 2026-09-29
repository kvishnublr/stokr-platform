package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IVRankService {

    private static final Logger log = LoggerFactory.getLogger(IVRankService.class);
    private static final double RISK_FREE_RATE = 0.065;

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final JdbcTemplate jdbc;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
        "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    // In-memory cache of latest IV data per underlying
    private final ConcurrentHashMap<String, Map<String, Object>> latestIV = new ConcurrentHashMap<>();

    public IVRankService(OptionChainService optionChainService,
                         ZerodhaSpotPriceFetcher spotFetcher,
                         JdbcTemplate jdbc) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
        this.jdbc = jdbc;
    }

    // Record IV snapshot every 30 minutes during market hours
    @Scheduled(cron = "0 */30 9-15 * * MON-FRI")
    public void recordIVSnapshots() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (!com.stokr.marketdata.MarketCalendar.isTradingDayToday() || now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 30))) return;

        for (String underlying : List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")) {
            try {
                Map<String, Object> snapshot = computeIVSnapshot(underlying);
                if (snapshot != null && !snapshot.isEmpty()) {
                    latestIV.put(underlying, snapshot);
                    saveSnapshot(underlying, snapshot);
                }
            } catch (Exception e) {
                log.error("IV snapshot failed for {}: {}", underlying, e.getMessage());
            }
        }
        log.info("IV snapshots recorded for all underlyings");
    }

    public Map<String, Object> computeIVSnapshot(String underlying) {
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey);
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey, futKey);
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return null;

        int step = OptionChainService.getStrikeStep(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);
        LocalDate expiry = optionChainService.getNearestExpiry(underlying);
        double yearsToExpiry = Math.max(
            Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays(), 0.5) / 365.0;

        // Fetch ATM CE and PE quotes
        List<String> instruments = new ArrayList<>();
        instruments.add(optionChainService.buildNfoSymbol(underlying, expiry, atmStrike, "CE"));
        instruments.add(optionChainService.buildNfoSymbol(underlying, expiry, atmStrike, "PE"));
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        OptionChainService.OptionQuote ceQ = quotes.get(instruments.get(0));
        OptionChainService.OptionQuote peQ = quotes.get(instruments.get(1));
        if (ceQ == null || peQ == null || ceQ.lastPrice <= 0 || peQ.lastPrice <= 0) return null;

        double ceIV = BlackScholesCalculator.impliedVolatility(
            ceQ.lastPrice, spot, atmStrike, yearsToExpiry, RISK_FREE_RATE, true, 0.01, 50);
        double peIV = BlackScholesCalculator.impliedVolatility(
            peQ.lastPrice, spot, atmStrike, yearsToExpiry, RISK_FREE_RATE, false, 0.01, 50);
        double atmIV = (ceIV + peIV) / 2.0;

        // Compute 5-day realized vol from DB (close prices)
        double rv5d = computeRealizedVol(underlying, 5);

        // Compute IV Rank from historical data
        double ivRank = computeIVRank(underlying, atmIV);

        double ivRvRatio = rv5d > 0 ? atmIV / rv5d : 0;

        // Determine regime
        String regime = determineRegime(atmIV, ivRank, spot, underlying);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("underlying", underlying);
        result.put("spotPrice", round2(spot));
        result.put("atmStrike", atmStrike);
        result.put("atmIV", round2(atmIV * 100));
        result.put("ceIV", round2(ceIV * 100));
        result.put("peIV", round2(peIV * 100));
        result.put("rv5d", round2(rv5d * 100));
        result.put("ivRank", round2(ivRank));
        result.put("ivRvRatio", round2(ivRvRatio));
        result.put("regime", regime);
        result.put("sellPremiumOk", ivRank > 40 && ivRvRatio > 1.15);
        result.put("timestamp", LocalDateTime.now(ZoneId.of("Asia/Kolkata")).toString());
        return result;
    }

    /** Underlyings with daily index candles in candle_data (symbol naming used by the candle loader). */
    private static final Map<String, String> CANDLE_SYMBOLS = Map.of("NIFTY", "NIFTY_50");

    /**
     * Annualised close-to-close realized volatility over the last {@code days} daily returns, from
     * the index's own price candles. Returns 0 when there is no price history — the previous version
     * used the standard deviation of stored IV snapshots, which is not a volatility of price at all
     * and made IV/RV look rich almost permanently.
     */
    private double computeRealizedVol(String underlying, int days) {
        String symbol = CANDLE_SYMBOLS.get(underlying);
        if (symbol == null) return 0;
        try {
            List<Double> closes = jdbc.queryForList(
                "SELECT close FROM candle_data WHERE symbol = ? AND timeframe = 'daily' ORDER BY timestamp DESC LIMIT ?",
                Double.class, symbol, days + 1);
            return realizedVol(closes);
        } catch (Exception e) {
            log.debug("Realized vol unavailable for {}: {}", underlying, e.getMessage());
            return 0;
        }
    }

    /** Annualised (√252) sample stdev of log returns; closes in any consistent order. 0 if &lt; 2 returns. */
    static double realizedVol(List<Double> closes) {
        if (closes == null || closes.size() < 3) return 0;
        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            double a = closes.get(i - 1), b = closes.get(i);
            if (a > 0 && b > 0) rets.add(Math.log(a / b));
        }
        if (rets.size() < 2) return 0;
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double ss = 0;
        for (double r : rets) ss += (r - mean) * (r - mean);
        return Math.sqrt(ss / (rets.size() - 1)) * Math.sqrt(252);
    }

    private double computeIVRank(String underlying, double currentIV) {
        try {
            // Get 52-week (or available) high and low IV
            List<Map<String, Object>> stats = jdbc.queryForList(
                "SELECT MIN(atm_iv) as min_iv, MAX(atm_iv) as max_iv FROM iv_history " +
                "WHERE underlying = ? AND snapshot_time > NOW() - INTERVAL '52 weeks'",
                underlying);
            if (stats.isEmpty() || stats.get(0).get("min_iv") == null) {
                return 50.0; // default mid-rank if no history
            }
            double minIV = ((Number) stats.get(0).get("min_iv")).doubleValue();
            double maxIV = ((Number) stats.get(0).get("max_iv")).doubleValue();
            if (maxIV <= minIV) return 50.0;
            return Math.max(0, Math.min(100, ((currentIV * 100 - minIV) / (maxIV - minIV)) * 100));
        } catch (Exception e) {
            return 50.0;
        }
    }

    private String determineRegime(double atmIV, double ivRank, double spot, String underlying) {
        // Simple regime detection:
        // LOW: IV < 13%, HIGH: IV > 20%, else MEDIUM
        double ivPct = atmIV * 100;
        if (ivPct < 13 && ivRank < 30) return "LOW";
        if (ivPct > 20 || ivRank > 70) return "HIGH";
        return "MEDIUM";
    }

    private void saveSnapshot(String underlying, Map<String, Object> snapshot) {
        try {
            jdbc.update(
                "INSERT INTO iv_history (underlying, snapshot_time, atm_iv, atm_iv_ce, atm_iv_pe, realized_vol_5d, iv_rank, iv_rv_ratio, regime) " +
                "VALUES (?, NOW(), ?, ?, ?, ?, ?, ?, ?)",
                underlying,
                snapshot.get("atmIV"),
                snapshot.get("ceIV"),
                snapshot.get("peIV"),
                snapshot.get("rv5d"),
                snapshot.get("ivRank"),
                snapshot.get("ivRvRatio"),
                snapshot.get("regime")
            );
        } catch (Exception e) {
            log.error("Failed to save IV snapshot for {}: {}", underlying, e.getMessage());
        }
    }

    public Map<String, Object> getCurrentIVData(String underlying) {
        Map<String, Object> cached = latestIV.get(underlying);
        if (cached != null) return cached;
        // Compute live if not cached
        return computeIVSnapshot(underlying);
    }

    public Map<String, Object> getAllIVData() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String underlying : List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")) {
            Map<String, Object> data = getCurrentIVData(underlying);
            if (data != null) result.put(underlying, data);
        }
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    public List<Map<String, Object>> getIVHistory(String underlying, int days) {
        try {
            return jdbc.queryForList(
                "SELECT underlying, snapshot_time, atm_iv, atm_iv_ce, atm_iv_pe, realized_vol_5d, " +
                "iv_rank, iv_rv_ratio, regime FROM iv_history " +
                "WHERE underlying = ? AND snapshot_time > NOW() - INTERVAL '" + days + " days' " +
                "ORDER BY snapshot_time DESC",
                underlying);
        } catch (Exception e) {
            log.error("Failed to fetch IV history for {}: {}", underlying, e.getMessage());
            return List.of();
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
