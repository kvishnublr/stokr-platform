package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

/**
 * Near-week vs monthly calendar (same strike CE or PE).
 * Executable: debit = farAsk − nearBid; credit = nearBid − farAsk.
 * Signal when calendar rich/cheap vs a small carry band — not risk-free arb.
 */
@Service
public class CalendarSpreadService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSpreadService.class);
    private static final double RISK_FREE_RATE = 0.065;
    private static final double MIN_EDGE_RS = 75.0;
    private static final double MAX_OPTION_SPREAD = 25.0;
    private static final double COSTS = 40.0;
    private static final long CACHE_TTL_MS = 1500;

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "calendar-scan");
        t.setDaemon(true);
        return t;
    });

    public CalendarSpreadService(OptionChainService optionChainService,
                                 OptionArbHistoryService historyService,
                                 ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public List<Map<String, Object>> scanCalendarSpreads(String underlying) {
        String uKey = underlying == null ? "ALL" : underlying.trim().toUpperCase(Locale.ROOT);
        Cached hit = cache.get(uKey);
        if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) {
            return copy(hit.opps);
        }

        List<String> targets = "ALL".equals(uKey)
                ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
                : List.of(uKey);

        Map<String, String> spotKeys = Map.of(
                "NIFTY", "NSE:NIFTY 50",
                "BANKNIFTY", "NSE:NIFTY BANK",
                "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
                "FINNIFTY", "NSE:NIFTY FIN SERVICE"
        );

        List<CompletableFuture<List<Map<String, Object>>>> futs = new ArrayList<>();
        for (String u : targets) {
            futs.add(CompletableFuture.supplyAsync(
                    () -> scanOne(u, spotKeys.getOrDefault(u, "NSE:NIFTY 50")), POOL));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futs.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            for (var f : futs) results.addAll(f.getNow(List.of()));
        } catch (Exception e) {
            log.warn("Calendar parallel scan incomplete: {}", e.getMessage());
            for (var f : futs) {
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    try { results.addAll(f.get()); } catch (Exception ignored) {}
                }
            }
        }

        results.sort((a, b) -> Double.compare(
                Math.abs(((Number) b.getOrDefault("edgeAfterCosts", 0.0)).doubleValue()),
                Math.abs(((Number) a.getOrDefault("edgeAfterCosts", 0.0)).doubleValue())));
        cache.put(uKey, new Cached(System.currentTimeMillis(), copy(results)));
        return results;
    }

    private List<Map<String, Object>> scanOne(String u, String spotKey) {
        try {
            String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotPriceFetcher, spotKey);
            double[] sf = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
            double spot = (sf != null && sf.length > 0 && sf[0] > 0) ? sf[0] : 0;
            double fut = (sf != null && sf.length > 1 && sf[1] > 0) ? sf[1] : 0;
            double ref = fut > 0 ? fut : spot;
            if (ref <= 0) return List.of();
            if (spot <= 0) spot = ref;
            return scanCalendarSpreads(u, spot, fut > 0 ? fut : ref);
        } catch (Exception e) {
            log.error("Calendar scan failed for {}: {}", u, e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> scanCalendarSpreads(String underlying, double spotPrice, double futuresPrice) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<ArbitrageOpportunity> toSave = new ArrayList<>();
        try {
            if (spotPrice <= 0) return results;

            LocalDate nearExpiry = optionChainService.getWeeklyExpiryDate(underlying);
            LocalDate farExpiry = optionChainService.getMonthlyExpiry(underlying);
            if (nearExpiry == null || farExpiry == null || !farExpiry.isAfter(nearExpiry)) return results;
            // Skip if weekly is already expired / today
            if (!nearExpiry.isAfter(LocalDate.now(ZoneId.of("Asia/Kolkata")))) return results;

            int atmStrike = optionChainService.getATMStrike(underlying, spotPrice);
            // Focus ATM ±2 for liquidity
            int step = OptionChainService.getStrikeStep(underlying);
            List<Integer> strikes = new ArrayList<>();
            for (int i = -2; i <= 2; i++) strikes.add(atmStrike + i * step);

            List<String> instruments = new ArrayList<>();
            for (int strike : strikes) {
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(underlying, nearExpiry, strike, "CE"));
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(underlying, nearExpiry, strike, "PE"));
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(underlying, farExpiry, strike, "CE"));
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(underlying, farExpiry, strike, "PE"));
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
            int lotSize = OptionChainService.getLotSize(underlying);
            log.info("Calendar scan {}: ATM={} near={} far={} quotes={}",
                    underlying, atmStrike, nearExpiry, farExpiry, quotes.size());

            for (int strike : strikes) {
                addSpread(results, toSave, quotes, underlying, nearExpiry, farExpiry, strike, "CE",
                        spotPrice, futuresPrice, lotSize);
                addSpread(results, toSave, quotes, underlying, nearExpiry, farExpiry, strike, "PE",
                        spotPrice, futuresPrice, lotSize);
            }

            if (!toSave.isEmpty()) {
                historyService.saveOpportunities(toSave, underlying, "CALENDAR_SPREAD");
            }

            results.sort((a, b) -> Double.compare(
                Math.abs(((Number) b.getOrDefault("edgeAfterCosts", 0.0)).doubleValue()),
                Math.abs(((Number) a.getOrDefault("edgeAfterCosts", 0.0)).doubleValue())));
            log.info("Calendar scan {} done: {} opportunities", underlying, results.size());
        } catch (Exception e) {
            log.error("Calendar spread scan failed for {}: {}", underlying, e.getMessage());
        }
        return results;
    }

    private void addSpread(List<Map<String, Object>> results,
                           List<ArbitrageOpportunity> toSave,
                           Map<String, OptionChainService.OptionQuote> quotes,
                           String underlying,
                           LocalDate nearExpiry,
                           LocalDate farExpiry,
                           int strike,
                           String optionType,
                           double spotPrice,
                           double futuresPrice,
                           int lotSize) {
        OptionChainService.OptionQuote near = firstQuote(quotes, underlying, nearExpiry, strike, optionType);
        OptionChainService.OptionQuote far = firstQuote(quotes, underlying, farExpiry, strike, optionType);
        if (near == null || far == null) return;
        if (near.bid <= 0 || near.ask <= 0 || far.bid <= 0 || far.ask <= 0) return;
        if ((near.ask - near.bid) > MAX_OPTION_SPREAD || (far.ask - far.bid) > MAX_OPTION_SPREAD) return;

        long nearDte = Math.max(1, Duration.between(
                LocalDate.now(ZoneId.of("Asia/Kolkata")).atStartOfDay(), nearExpiry.atStartOfDay()).toDays());
        long farDte = Math.max(nearDte + 1, Duration.between(
                LocalDate.now(ZoneId.of("Asia/Kolkata")).atStartOfDay(), farExpiry.atStartOfDay()).toDays());

        // Debit calendar (BUY far / SELL near): pay farAsk, receive nearBid
        double debit = far.ask - near.bid;
        // Credit calendar (SELL far / BUY near): receive farBid, pay nearAsk
        double credit = far.bid - near.ask;

        double carryBand = Math.max(2.0, Math.abs(futuresPrice - spotPrice) * ((farDte - nearDte) / 365.0) * RISK_FREE_RATE * 8);
        // Heuristic: calendar debit should roughly sit in a band; large debit = sell calendar, tiny/negative = buy
        double fairish = Math.max(carryBand, (near.bid + near.ask) / 4.0); // rough floor vs near premium

        // SELL calendar if debit is rich vs fairish
        double sellEdgePts = debit - fairish;
        double buyEdgePts = fairish - Math.max(debit, 0.1); // buy if cheap

        if (sellEdgePts * lotSize - COSTS >= MIN_EDGE_RS && sellEdgePts <= 40) {
            Map<String, Object> opp = baseOpp(underlying, optionType, strike, nearExpiry, farExpiry,
                    nearDte, farDte, near, far, lotSize, spotPrice, futuresPrice);
            opp.put("spread", round2(debit));
            opp.put("expectedCarry", round2(fairish));
            opp.put("edgePoints", round2(sellEdgePts));
            opp.put("edgeAfterCosts", round2(sellEdgePts * lotSize - COSTS));
            opp.put("action", "SELL_FAR_BUY_NEAR");
            opp.put("legs", String.format("SELL %s %s @ %.1f | BUY %s %s @ %.1f",
                    farExpiry, optionType, far.bid, nearExpiry, optionType, near.ask));
            opp.put("description", "Calendar rich — sell far / buy near (credit/debit depending on fills)");
            opp.put("strategyType", "CALENDAR_SPREAD");
            results.add(opp);
            toSave.add(toArb(opp, near, far));
        } else if (buyEdgePts * lotSize - COSTS >= MIN_EDGE_RS && buyEdgePts <= 40 && credit < fairish) {
            Map<String, Object> opp = baseOpp(underlying, optionType, strike, nearExpiry, farExpiry,
                    nearDte, farDte, near, far, lotSize, spotPrice, futuresPrice);
            opp.put("spread", round2(debit));
            opp.put("expectedCarry", round2(fairish));
            opp.put("edgePoints", round2(buyEdgePts));
            opp.put("edgeAfterCosts", round2(buyEdgePts * lotSize - COSTS));
            opp.put("action", "BUY_FAR_SELL_NEAR");
            opp.put("legs", String.format("BUY %s %s @ %.1f | SELL %s %s @ %.1f",
                    farExpiry, optionType, far.ask, nearExpiry, optionType, near.bid));
            opp.put("description", "Calendar cheap — buy far / sell near");
            opp.put("strategyType", "CALENDAR_SPREAD");
            results.add(opp);
            toSave.add(toArb(opp, near, far));
        }
    }

    private Map<String, Object> baseOpp(String underlying, String optionType, int strike,
                                        LocalDate nearExpiry, LocalDate farExpiry,
                                        long nearDte, long farDte,
                                        OptionChainService.OptionQuote near,
                                        OptionChainService.OptionQuote far,
                                        int lotSize, double spot, double fut) {
        Map<String, Object> opp = new LinkedHashMap<>();
        opp.put("type", "CALENDAR_SPREAD");
        opp.put("underlying", underlying);
        opp.put("optionType", optionType);
        opp.put("strike", strike);
        opp.put("nearExpiry", nearExpiry.toString());
        opp.put("farExpiry", farExpiry.toString());
        opp.put("expiryDate", farExpiry.toString());
        opp.put("daysNear", nearDte);
        opp.put("daysFar", farDte);
        opp.put("daysToExpiry", (double) farDte);
        opp.put("nearPrice", round2((near.bid + near.ask) / 2));
        opp.put("farPrice", round2((far.bid + far.ask) / 2));
        opp.put("nearBid", near.bid);
        opp.put("nearAsk", near.ask);
        opp.put("farBid", far.bid);
        opp.put("farAsk", far.ask);
        opp.put("lotSize", lotSize);
        opp.put("spotPrice", spot);
        opp.put("futuresPrice", fut);
        opp.put("guaranteedFill", false);
        return opp;
    }

    private ArbitrageOpportunity toArb(Map<String, Object> m,
                                       OptionChainService.OptionQuote near,
                                       OptionChainService.OptionQuote far) {
        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.underlying = String.valueOf(m.get("underlying"));
        opp.strike = ((Number) m.get("strike")).intValue();
        opp.type = "CALENDAR_SPREAD";
        opp.action = String.valueOf(m.get("action"));
        opp.legs = String.valueOf(m.get("legs"));
        opp.description = String.valueOf(m.get("description"));
        opp.spotPrice = ((Number) m.getOrDefault("spotPrice", 0)).doubleValue();
        opp.futuresPrice = ((Number) m.getOrDefault("futuresPrice", 0)).doubleValue();
        opp.cePrice = near.lastPrice;
        opp.pePrice = far.lastPrice;
        opp.ceBid = near.bid;
        opp.ceAsk = near.ask;
        opp.peBid = far.bid;
        opp.peAsk = far.ask;
        opp.edgePoints = ((Number) m.get("edgePoints")).doubleValue();
        opp.edgeAfterCosts = ((Number) m.get("edgeAfterCosts")).doubleValue();
        opp.confidence = 60;
        opp.daysToExpiry = ((Number) m.getOrDefault("daysToExpiry", 7)).doubleValue();
        try {
            opp.expiryDate = LocalDate.parse(String.valueOf(m.get("expiryDate")));
        } catch (Exception e) {
            opp.expiryDate = LocalDate.now().plusDays(7);
        }
        opp.detectedAt = LocalDateTime.now();
        return opp;
    }

    private OptionChainService.OptionQuote firstQuote(Map<String, OptionChainService.OptionQuote> quotes,
                                                      String underlying, LocalDate expiry, int strike, String type) {
        for (String sym : optionChainService.buildNfoSymbolCandidatesPublic(underlying, expiry, strike, type)) {
            OptionChainService.OptionQuote q = quotes.get(sym);
            if (q != null && (q.bid > 0 || q.ask > 0 || q.lastPrice > 0)) return q;
        }
        return null;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static List<Map<String, Object>> copy(List<Map<String, Object>> src) {
        List<Map<String, Object>> out = new ArrayList<>(src.size());
        for (Map<String, Object> m : src) out.add(new LinkedHashMap<>(m));
        return out;
    }

    private record Cached(long at, List<Map<String, Object>> opps) {}
}
