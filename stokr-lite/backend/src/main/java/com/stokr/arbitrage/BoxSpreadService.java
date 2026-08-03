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
 * 4-leg box spread scanner (LONG/SHORT), same-expiry legs.
 * Fair value = DF·(K2−K1) (futures-style index options). Bid/ask only.
 */
@Service
public class BoxSpreadService {

    private static final Logger log = LoggerFactory.getLogger(BoxSpreadService.class);
    private static final double RISK_FREE = 0.065;
    private static final double MIN_BOX_EDGE_AFTER_COSTS = 75.0;
    private static final double MAX_OPTION_SPREAD = 20.0;
    private static final double MAX_EDGE_PTS = 20.0;
    private static final double BOX_COSTS = 80.0; // ~4 legs discount brokerage
    private static final long CACHE_TTL_MS = 1500;

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "box-scan");
        t.setDaemon(true);
        return t;
    });

    public BoxSpreadService(OptionChainService optionChainService,
                            OptionArbHistoryService historyService,
                            ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public List<Map<String, Object>> scanBoxSpread(String underlying) {
        return scanBoxSpread(underlying, "BOTH");
    }

    /** @param expiryMode WEEKLY | MONTHLY | BOTH */
    public List<Map<String, Object>> scanBoxSpread(String underlying, String expiryMode) {
        String mode = expiryMode == null ? "BOTH" : expiryMode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("WEEKLY", "MONTHLY", "BOTH").contains(mode)) mode = "BOTH";
        String uKey = underlying == null ? "ALL" : underlying.trim().toUpperCase(Locale.ROOT);
        String cacheKey = uKey + "|" + mode;
        Cached hit = cache.get(cacheKey);
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
            final String m = mode;
            futs.add(CompletableFuture.supplyAsync(() -> scanOne(u, spotKeys.getOrDefault(u, "NSE:NIFTY 50"), m), POOL));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futs.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            for (var f : futs) results.addAll(f.getNow(List.of()));
        } catch (Exception e) {
            log.warn("Box parallel scan incomplete: {}", e.getMessage());
            for (var f : futs) {
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    try { results.addAll(f.get()); } catch (Exception ignored) {}
                }
            }
        }

        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("edgeAfterCosts", 0)).doubleValue(),
                ((Number) a.getOrDefault("edgeAfterCosts", 0)).doubleValue()));
        cache.put(cacheKey, new Cached(System.currentTimeMillis(), copy(results)));
        return results;
    }

    private List<Map<String, Object>> scanOne(String u, String spotKey, String mode) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotPriceFetcher, spotKey);
            double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
            double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
            double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : 0;
            double ref = fut > 0 ? fut : spot;
            if (ref <= 0) {
                log.warn("No spot/fut for {} — skip box scan", u);
                return results;
            }
            if (spot <= 0) spot = ref;

            LocalDate weekly = optionChainService.getWeeklyExpiryDate(u);
            LocalDate monthly = optionChainService.getMonthlyExpiry(u);
            List<LocalDate> expiries = new ArrayList<>();
            if (("WEEKLY".equals(mode) || "BOTH".equals(mode)) && weekly != null) {
                // Skip same-day expiry (illiquid / pin risk)
                if (weekly.isAfter(LocalDate.now(ZoneId.of("Asia/Kolkata")))) {
                    expiries.add(weekly);
                }
            }
            if (("MONTHLY".equals(mode) || "BOTH".equals(mode)) && monthly != null) {
                if (!expiries.contains(monthly)) expiries.add(monthly);
            }

            for (LocalDate expiry : expiries) {
                List<ArbitrageOpportunity> opps = scanBoxSpreadForUnderlying(u, spot, fut > 0 ? fut : ref, expiry);
                if (opps == null || opps.isEmpty()) continue;
                String expiryMode = expiry.equals(monthly) ? "MONTHLY" : "WEEKLY";
                // Stamp expiry mode into description so after-hours board can filter WEEKLY/MONTHLY
                for (ArbitrageOpportunity opp : opps) {
                    String d = opp.description != null ? opp.description : "";
                    if (!d.toUpperCase(Locale.ROOT).contains(expiryMode)) {
                        opp.description = "[" + expiryMode + "] " + d;
                    }
                }
                historyService.saveOpportunities(opps, u, "BOX_SPREAD");
                for (ArbitrageOpportunity opp : opps) {
                    Map<String, Object> map = opp.toMap();
                    map.put("strategyType", "BOX_SPREAD");
                    map.put("expiryMode", expiryMode);
                    map.put("guaranteedFill", false);
                    map.put("boxEdgeInr", opp.edgeAfterCosts);
                    if (opp.costBreakdown != null) {
                        for (String k : List.of("lowerStrike", "upperStrike", "boxCost", "payoff", "fairValue", "width")) {
                            if (opp.costBreakdown.containsKey(k)) map.put(k, opp.costBreakdown.get(k));
                        }
                    }
                    results.add(map);
                }
            }
        } catch (Exception e) {
            log.error("Error scanning Box Spread for {}: {}", u, e.getMessage(), e);
        }
        return results;
    }

    public List<ArbitrageOpportunity> scanBoxSpreadForUnderlying(String underlying, double spotPrice, double futuresPrice) {
        LocalDate weekly = optionChainService.getWeeklyExpiryDate(underlying);
        return scanBoxSpreadForUnderlying(underlying, spotPrice, futuresPrice, weekly);
    }

    public List<ArbitrageOpportunity> scanBoxSpreadForUnderlying(String underlying, double spotPrice, double futuresPrice,
                                                                 LocalDate expiryDate) {
        List<ArbitrageOpportunity> opps = new ArrayList<>();
        try {
            if (expiryDate == null) return opps;
            double ref = futuresPrice > 0 ? futuresPrice : spotPrice;
            int atmStrike = optionChainService.getATMStrike(underlying, ref);
            List<Integer> strikes = new ArrayList<>();
            int step = OptionChainService.getStrikeStep(underlying);
            for (int i = -4; i <= 4; i++) strikes.add(atmStrike + i * step);

            double daysToExpiry = Math.max(0.5,
                    Duration.between(LocalDate.now(ZoneId.of("Asia/Kolkata")).atStartOfDay(),
                            expiryDate.atStartOfDay()).toDays());
            double years = daysToExpiry / 365.0;
            double df = Math.exp(-RISK_FREE * years);

            List<String> instruments = new ArrayList<>();
            for (int s : strikes) {
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(underlying, expiryDate, s, "CE"));
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(underlying, expiryDate, s, "PE"));
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
            int lotSize = OptionChainService.getLotSize(underlying);
            log.info("Box scan {}: ATM={} expiry={} quotes={} strikes={}",
                    underlying, atmStrike, expiryDate, quotes.size(), strikes.size());

            double bestPts = 0;
            for (int i = 0; i < strikes.size(); i++) {
                for (int j = i + 1; j < strikes.size(); j++) {
                    int k1 = strikes.get(i);
                    int k2 = strikes.get(j);
                    double width = k2 - k1;
                    if (width <= 0) continue;

                    OptionChainService.OptionQuote ce1 = firstQuote(quotes, underlying, expiryDate, k1, "CE");
                    OptionChainService.OptionQuote pe1 = firstQuote(quotes, underlying, expiryDate, k1, "PE");
                    OptionChainService.OptionQuote ce2 = firstQuote(quotes, underlying, expiryDate, k2, "CE");
                    OptionChainService.OptionQuote pe2 = firstQuote(quotes, underlying, expiryDate, k2, "PE");
                    if (ce1 == null || pe1 == null || ce2 == null || pe2 == null) continue;
                    if (!hasBook(ce1) || !hasBook(pe1) || !hasBook(ce2) || !hasBook(pe2)) continue;
                    if (spread(ce1) > MAX_OPTION_SPREAD || spread(pe1) > MAX_OPTION_SPREAD) continue;
                    if (spread(ce2) > MAX_OPTION_SPREAD || spread(pe2) > MAX_OPTION_SPREAD) continue;

                    double fair = width * df;

                    // LONG BOX: BUY k1CE@ask, SELL k1PE@bid, SELL k2CE@bid, BUY k2PE@ask
                    double longCost = ce1.ask - pe1.bid - ce2.bid + pe2.ask;
                    double longEdgePts = fair - longCost;
                    // Mid check — reject one-sided stale books
                    double longMid = mid(ce1) - mid(pe1) - mid(ce2) + mid(pe2);
                    double longMidEdge = fair - longMid;

                    if (longEdgePts > bestPts) bestPts = longEdgePts;
                    if (longEdgePts >= 1.5 && longEdgePts <= MAX_EDGE_PTS
                            && longMidEdge >= longEdgePts * 0.35) {
                        double longNet = longEdgePts * lotSize - BOX_COSTS;
                        if (longNet >= MIN_BOX_EDGE_AFTER_COSTS) {
                            opps.add(buildBox(underlying, k1, k2, width, fair, longCost, longEdgePts, longNet,
                                    spotPrice, futuresPrice, daysToExpiry, expiryDate, lotSize, true,
                                    ce1, pe1, ce2, pe2));
                        }
                    }

                    // SHORT BOX: SELL k1CE@bid, BUY k1PE@ask, BUY k2CE@ask, SELL k2PE@bid
                    double shortCredit = ce1.bid - pe1.ask - ce2.ask + pe2.bid;
                    double shortEdgePts = shortCredit - fair;
                    double shortMid = mid(ce1) - mid(pe1) - mid(ce2) + mid(pe2);
                    double shortMidEdge = shortMid - fair;
                    if (shortEdgePts > bestPts) bestPts = shortEdgePts;
                    if (shortEdgePts >= 1.5 && shortEdgePts <= MAX_EDGE_PTS
                            && shortMidEdge >= shortEdgePts * 0.35) {
                        double shortNet = shortEdgePts * lotSize - BOX_COSTS;
                        if (shortNet >= MIN_BOX_EDGE_AFTER_COSTS) {
                            opps.add(buildBox(underlying, k1, k2, width, fair, shortCredit, shortEdgePts, shortNet,
                                    spotPrice, futuresPrice, daysToExpiry, expiryDate, lotSize, false,
                                    ce1, pe1, ce2, pe2));
                        }
                    }
                }
            }

            opps.sort((a, b) -> Double.compare(b.edgeAfterCosts, a.edgeAfterCosts));
            if (opps.isEmpty()) {
                log.info("Box scan {} done: 0 opps (best near-miss {} pts) expiry={}",
                        underlying, String.format("%.1f", bestPts), expiryDate);
            } else {
                log.info("Box scan {} done: {} opportunities expiry={}", underlying, opps.size(), expiryDate);
            }
        } catch (Exception e) {
            log.error("Error calculating Box Spread for {}: {}", underlying, e.getMessage(), e);
        }
        return opps;
    }

    private ArbitrageOpportunity buildBox(String underlying, int k1, int k2, double width, double fair,
                                          double boxCost, double edgePts, double netEdge,
                                          double spot, double fut, double dte, LocalDate expiry,
                                          int lotSize, boolean isLong,
                                          OptionChainService.OptionQuote ce1,
                                          OptionChainService.OptionQuote pe1,
                                          OptionChainService.OptionQuote ce2,
                                          OptionChainService.OptionQuote pe2) {
        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.underlying = underlying;
        opp.strike = k1;
        opp.type = "BOX_SPREAD";
        opp.action = (isLong ? "LONG BOX" : "SHORT BOX") + " (" + k1 + "/" + k2 + ")";
        opp.spotPrice = spot;
        opp.futuresPrice = fut;
        opp.cePrice = ce1.lastPrice;
        opp.pePrice = pe1.lastPrice;
        opp.ceBid = ce1.bid;
        opp.ceAsk = ce1.ask;
        opp.peBid = pe1.bid;
        opp.peAsk = pe1.ask;
        opp.edgePoints = Math.round(edgePts * 10.0) / 10.0;
        opp.edgeAfterCosts = Math.round(netEdge * 10.0) / 10.0;
        opp.confidence = Math.min(99.0, 55.0 + Math.abs(edgePts) * 2.0);
        opp.daysToExpiry = dte;
        opp.expiryDate = expiry;
        opp.detectedAt = LocalDateTime.now();
        if (isLong) {
            opp.legs = String.format(
                    "BUY %d CE @ %.1f | SELL %d PE @ %.1f | SELL %d CE @ %.1f | BUY %d PE @ %.1f",
                    k1, ce1.ask, k1, pe1.bid, k2, ce2.bid, k2, pe2.ask);
            opp.description = "Long box cheap vs DF·(K2−K1) — buy low synth / sell high synth";
        } else {
            opp.legs = String.format(
                    "SELL %d CE @ %.1f | BUY %d PE @ %.1f | BUY %d CE @ %.1f | SELL %d PE @ %.1f",
                    k1, ce1.bid, k1, pe1.ask, k2, ce2.ask, k2, pe2.bid);
            opp.description = "Short box rich vs DF·(K2−K1) — sell low synth / buy high synth";
        }

        Map<String, Double> costs = new LinkedHashMap<>();
        costs.put("lowerStrike", (double) k1);
        costs.put("upperStrike", (double) k2);
        costs.put("width", width);
        costs.put("fairValue", Math.round(fair * 100.0) / 100.0);
        costs.put("boxCost", Math.round(boxCost * 100.0) / 100.0);
        costs.put("payoff", width);
        costs.put("lotSize", (double) lotSize);
        costs.put("costsInr", BOX_COSTS);
        costs.put("netInr", opp.edgeAfterCosts);
        opp.costBreakdown = costs;
        return opp;
    }

    private OptionChainService.OptionQuote firstQuote(Map<String, OptionChainService.OptionQuote> quotes,
                                                      String underlying, LocalDate expiry, int strike, String type) {
        for (String sym : optionChainService.buildNfoSymbolCandidatesPublic(underlying, expiry, strike, type)) {
            OptionChainService.OptionQuote q = quotes.get(sym);
            if (q != null && (q.lastPrice > 0 || q.bid > 0 || q.ask > 0)) return q;
        }
        return null;
    }

    private static boolean hasBook(OptionChainService.OptionQuote q) {
        return q.bid > 0 && q.ask > 0 && q.ask >= q.bid;
    }

    private static double spread(OptionChainService.OptionQuote q) {
        return q.ask - q.bid;
    }

    private static double mid(OptionChainService.OptionQuote q) {
        return (q.bid + q.ask) / 2.0;
    }

    private static List<Map<String, Object>> copy(List<Map<String, Object>> src) {
        List<Map<String, Object>> out = new ArrayList<>(src.size());
        for (Map<String, Object> m : src) out.add(new LinkedHashMap<>(m));
        return out;
    }

    private record Cached(long at, List<Map<String, Object>> opps) {}
}
