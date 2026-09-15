package com.stokr.smartstrategy.morningtheta;

import com.stokr.arbitrage.OptionChainService;
import com.stokr.arbitrage.ZerodhaSpotPriceFetcher;
import com.stokr.arbitrage.FuturesKeyResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MorningRangeTracker {

    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final OptionChainService optionChainService;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK"
    );
    private static final List<String> UNDERLYINGS = List.of("NIFTY", "BANKNIFTY");
    private static final LocalTime TRACKING_START = LocalTime.of(9, 15);
    private static final LocalTime TRACKING_END = LocalTime.of(10, 15);

    private final ConcurrentHashMap<String, RangeData> rangeMap = new ConcurrentHashMap<>();
    private volatile LocalDate trackingDate;

    public MorningRangeTracker(ZerodhaSpotPriceFetcher spotFetcher, OptionChainService optionChainService) {
        this.spotFetcher = spotFetcher;
        this.optionChainService = optionChainService;
    }

    public record RangeData(
        String underlying, double openPrice, double high, double low,
        double currentSpot, double rangePercent,
        DayType dayType, TrendDirection trend,
        double atmIV, boolean frozen, long updatedAt
    ) {}

    public enum DayType { QUIET, NORMAL, TRENDING, SPIKE, UNKNOWN }
    public enum TrendDirection { BULLISH, BEARISH, NEUTRAL }

    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    public void trackRange() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        // Reset tracker at start of new day
        if (trackingDate == null || !trackingDate.equals(today)) {
            rangeMap.clear();
            trackingDate = today;
        }

        // Before market or after close — nothing to do
        if (now.isBefore(TRACKING_START) || now.isAfter(MARKET_CLOSE)) {
            return;
        }

        // If we're past the tracking window and rangeMap is empty (restart recovery),
        // build synthetic range data from current spot so the page isn't blank
        if (now.isAfter(TRACKING_END) && rangeMap.isEmpty()) {
            recoverRangeData();
            return;
        }

        // Normal tracking during 9:15-10:16
        if (now.isAfter(TRACKING_END.plusMinutes(1))) {
            // Outside tracking window but data exists — keep updating current spot
            updateCurrentSpot();
            return;
        }

        for (String underlying : UNDERLYINGS) {
            try {
                String spotKey = SPOT_KEYS.get(underlying);
                String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey);
                double[] spotFut = spotFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                if (spot <= 0) continue;

                RangeData existing = rangeMap.get(underlying);
                if (existing != null && existing.frozen) continue;

                double open = (existing != null) ? existing.openPrice : spot;
                double high = (existing != null) ? Math.max(existing.high, spot) : spot;
                double low = (existing != null) ? Math.min(existing.low, spot) : spot;
                double rangePercent = (high - low) / open * 100;

                boolean shouldFreeze = now.isAfter(TRACKING_END);
                DayType dayType = classifyDay(rangePercent);
                TrendDirection trend = classifyTrend(spot, open, high, low);

                double atmIV = shouldFreeze ? computeAtmIV(underlying, spot) : 0;

                RangeData data = new RangeData(
                    underlying, open, high, low, spot, Math.round(rangePercent * 100.0) / 100.0,
                    dayType, trend, atmIV, shouldFreeze, System.currentTimeMillis()
                );
                rangeMap.put(underlying, data);

                if (shouldFreeze) {
                    log.info("MORNING_RANGE [{}]: range={}% ({}-{}), type={}, trend={}, IV={}",
                        underlying, Math.round(rangePercent * 100.0) / 100.0, Math.round(low), Math.round(high),
                        dayType, trend, Math.round(atmIV * 10.0) / 10.0);
                }
            } catch (Exception e) {
                log.debug("Morning range tracking error for {}: {}", underlying, e.getMessage());
            }
        }
    }

    private void recoverRangeData() {
        log.info("MORNING_RANGE: Recovering range data after restart (past 10:15, rangeMap empty)");
        for (String underlying : UNDERLYINGS) {
            try {
                String spotKey = SPOT_KEYS.get(underlying);
                String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey);
                double[] spotFut = spotFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                if (spot <= 0) continue;

                // Use a conservative 0.5% range estimate since we missed the actual tracking
                double estimatedRange = spot * 0.005;
                double syntheticHigh = spot + estimatedRange / 2;
                double syntheticLow = spot - estimatedRange / 2;
                double rangePercent = 0.5;

                DayType dayType = DayType.NORMAL;
                TrendDirection trend = TrendDirection.NEUTRAL;
                double atmIV = computeAtmIV(underlying, spot);

                RangeData data = new RangeData(
                    underlying, spot, syntheticHigh, syntheticLow, spot,
                    rangePercent, dayType, trend, atmIV, true, System.currentTimeMillis()
                );
                rangeMap.put(underlying, data);

                log.info("MORNING_RANGE [{}]: RECOVERED — spot={}, syntheticRange={}-{}, type=NORMAL, IV={}",
                    underlying, Math.round(spot), Math.round(syntheticLow), Math.round(syntheticHigh),
                    Math.round(atmIV * 10.0) / 10.0);
            } catch (Exception e) {
                log.warn("Morning range recovery failed for {}: {}", underlying, e.getMessage());
            }
        }
    }

    private void updateCurrentSpot() {
        for (String underlying : UNDERLYINGS) {
            try {
                RangeData existing = rangeMap.get(underlying);
                if (existing == null) continue;

                String spotKey = SPOT_KEYS.get(underlying);
                String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey);
                double[] spotFut = spotFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                if (spot <= 0) continue;

                TrendDirection trend = classifyTrend(spot, existing.openPrice, existing.high, existing.low);

                RangeData updated = new RangeData(
                    existing.underlying, existing.openPrice, existing.high, existing.low,
                    spot, existing.rangePercent, existing.dayType, trend,
                    existing.atmIV, existing.frozen, System.currentTimeMillis()
                );
                rangeMap.put(underlying, updated);
            } catch (Exception e) {
                log.debug("Spot update error for {}: {}", underlying, e.getMessage());
            }
        }
    }

    private DayType classifyDay(double rangePercent) {
        if (rangePercent < 0.4) return DayType.QUIET;
        if (rangePercent < 0.7) return DayType.NORMAL;
        if (rangePercent < 1.2) return DayType.TRENDING;
        return DayType.SPIKE;
    }

    private TrendDirection classifyTrend(double current, double open, double high, double low) {
        double range = high - low;
        if (range <= 0) return TrendDirection.NEUTRAL;
        double positionInRange = (current - low) / range;
        if (positionInRange > 0.65) return TrendDirection.BULLISH;
        if (positionInRange < 0.35) return TrendDirection.BEARISH;
        return TrendDirection.NEUTRAL;
    }

    private double computeAtmIV(String underlying, double spot) {
        try {
            int step = OptionChainService.getStrikeStep(underlying);
            int atmStrike = (int) (Math.round(spot / step) * step);
            LocalDate expiry = optionChainService.getNearestExpiry(underlying);

            List<String> instruments = new ArrayList<>();
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, atmStrike, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, atmStrike, "PE"));
            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

            double cePrice = 0, pePrice = 0;
            for (var entry : quotes.entrySet()) {
                if (entry.getValue().lastPrice > 0) {
                    if (entry.getKey().contains("CE")) cePrice = entry.getValue().lastPrice;
                    else pePrice = entry.getValue().lastPrice;
                }
            }
            double straddle = cePrice + pePrice;
            if (straddle <= 0 || spot <= 0) return 0;
            // Rough IV approximation: straddle_price / spot * sqrt(365/DTE) * 100
            LocalDate todayIST = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            long dte = Math.max(1, java.time.Duration.between(
                todayIST.atStartOfDay(), expiry.atStartOfDay()).toDays());
            return straddle / spot * Math.sqrt(365.0 / dte) * 100;
        } catch (Exception e) {
            return 0;
        }
    }

    public RangeData getRange(String underlying) {
        return rangeMap.get(underlying);
    }

    public Map<String, RangeData> getAllRanges() {
        return new LinkedHashMap<>(rangeMap);
    }

    public boolean isRangeFrozen(String underlying) {
        RangeData data = rangeMap.get(underlying);
        return data != null && data.frozen;
    }

    public boolean isTrackingPhase() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return !now.isBefore(TRACKING_START) && now.isBefore(TRACKING_END);
    }
}
