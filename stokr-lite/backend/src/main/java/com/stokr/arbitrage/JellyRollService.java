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
 * Jelly Roll = calendar of synthetics (term-structure twin of Bid Parity).
 * Fair: C−P = DF·(F−K) on near & far using listed monthly F as F.
 * Long jelly: buy far synth / sell near synth when market pay &lt; fair.
 * Short jelly: opposite.
 */
@Service
public class JellyRollService {

    private static final Logger log = LoggerFactory.getLogger(JellyRollService.class);
    private static final double RISK_FREE = 0.065;
    private static final double MIN_EDGE_RS = 150.0;
    private static final double MAX_EDGE_RS = 800.0;
    private static final double MAX_EDGE_PTS = 15.0;
    private static final double MAX_OPTION_SPREAD = 20.0;
    private static final double COSTS = 80.0; // 4 option legs
    private static final long CACHE_TTL_MS = 1500;

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedForward> forwardCache = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "jelly-scan");
        t.setDaemon(true);
        return t;
    });

    public JellyRollService(OptionChainService optionChainService,
                            OptionArbHistoryService historyService,
                            ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public List<Map<String, Object>> scanJellyRoll(String underlying) {
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
            CompletableFuture.allOf(futs.toArray(CompletableFuture[]::new)).get(12, TimeUnit.SECONDS);
            for (var f : futs) results.addAll(f.getNow(List.of()));
        } catch (Exception e) {
            log.warn("Jelly parallel scan incomplete: {}", e.getMessage());
            for (var f : futs) {
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    try { results.addAll(f.get()); } catch (Exception ignored) {}
                }
            }
        }

        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("edgeAfterCosts", 0)).doubleValue(),
                ((Number) a.getOrDefault("edgeAfterCosts", 0)).doubleValue()));
        cache.put(uKey, new Cached(System.currentTimeMillis(), copy(results)));
        return results;
    }

    /** Implied forward strip: weekly + monthly ATM synth F vs listed monthly FUT. */
    public List<Map<String, Object>> impliedForwardStrip(String underlying) {
        String uKey = underlying == null ? "ALL" : underlying.trim().toUpperCase(Locale.ROOT);
        CachedForward hit = forwardCache.get(uKey);
        if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) {
            return copy(hit.rows);
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

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String u : targets) {
            try {
                Map<String, Object> row = forwardOne(u, spotKeys.getOrDefault(u, "NSE:NIFTY 50"));
                if (row != null) rows.add(row);
            } catch (Exception e) {
                log.warn("Forward strip failed for {}: {}", u, e.getMessage());
            }
        }
        forwardCache.put(uKey, new CachedForward(System.currentTimeMillis(), copy(rows)));
        return rows;
    }

    private Map<String, Object> forwardOne(String u, String spotKey) {
        String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotPriceFetcher, spotKey);
        double[] sf = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
        double spot = (sf != null && sf.length > 0 && sf[0] > 0) ? sf[0] : 0;
        double fut = (sf != null && sf.length > 1 && sf[1] > 0) ? sf[1] : 0;
        double ref = fut > 0 ? fut : spot;
        if (ref <= 0) return null;

        LocalDate weekly = optionChainService.getWeeklyExpiryDate(u);
        LocalDate monthly = optionChainService.getMonthlyExpiry(u);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        int atm = optionChainService.getATMStrike(u, ref);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("underlying", u);
        row.put("spot", round2(spot > 0 ? spot : ref));
        row.put("futures", round2(fut > 0 ? fut : ref));
        row.put("atmStrike", atm);
        row.put("monthlyExpiry", monthly != null ? monthly.toString() : null);
        row.put("weeklyExpiry", weekly != null ? weekly.toString() : null);

        if (weekly != null && weekly.isAfter(today)) {
            Map<String, Object> w = expiryForward(u, weekly, atm, fut > 0 ? fut : ref);
            if (w != null) row.put("weekly", w);
        }
        if (monthly != null) {
            Map<String, Object> m = expiryForward(u, monthly, atm, fut > 0 ? fut : ref);
            if (m != null) row.put("monthly", m);
        }
        return row;
    }

    private Map<String, Object> expiryForward(String u, LocalDate expiry, int atm, double fut) {
        double dte = Math.max(0.5, Duration.between(
                LocalDate.now(ZoneId.of("Asia/Kolkata")).atStartOfDay(),
                expiry.atStartOfDay()).toDays());
        double df = Math.exp(-RISK_FREE * dte / 365.0);
        List<String> instruments = new ArrayList<>();
        int step = OptionChainService.getStrikeStep(u);
        for (int off : new int[]{0, step, -step}) {
            int k = atm + off;
            instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(u, expiry, k, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(u, expiry, k, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
        Double imp = optionChainService.impliedForwardFromAtm(quotes, u, expiry, atm, df);
        if (imp == null || imp <= 0) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expiry", expiry.toString());
        m.put("dte", round2(dte));
        m.put("df", round4(df));
        m.put("impliedForward", round2(imp));
        m.put("basisVsFut", round2(imp - fut));
        return m;
    }

    private List<Map<String, Object>> scanOne(String u, String spotKey) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<ArbitrageOpportunity> toSave = new ArrayList<>();
        try {
            String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotPriceFetcher, spotKey);
            double[] sf = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
            double spot = (sf != null && sf.length > 0 && sf[0] > 0) ? sf[0] : 0;
            double fut = (sf != null && sf.length > 1 && sf[1] > 0) ? sf[1] : 0;
            double ref = fut > 0 ? fut : spot;
            if (ref <= 0) {
                log.warn("No spot/fut for {} — skip jelly", u);
                return results;
            }
            if (spot <= 0) spot = ref;
            if (fut <= 0) fut = ref;

            LocalDate nearExpiry = optionChainService.getWeeklyExpiryDate(u);
            LocalDate farExpiry = optionChainService.getMonthlyExpiry(u);
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            if (nearExpiry == null || farExpiry == null || !farExpiry.isAfter(nearExpiry)) return results;
            if (!nearExpiry.isAfter(today)) return results;

            int atm = optionChainService.getATMStrike(u, ref);
            int step = OptionChainService.getStrikeStep(u);
            List<Integer> strikes = new ArrayList<>();
            for (int i = -2; i <= 2; i++) strikes.add(atm + i * step);

            List<String> instruments = new ArrayList<>();
            for (int k : strikes) {
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(u, nearExpiry, k, "CE"));
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(u, nearExpiry, k, "PE"));
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(u, farExpiry, k, "CE"));
                instruments.addAll(optionChainService.buildNfoSymbolCandidatesPublic(u, farExpiry, k, "PE"));
            }
            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
            int lotSize = OptionChainService.getLotSize(u);

            double nearDte = Math.max(0.5, Duration.between(today.atStartOfDay(), nearExpiry.atStartOfDay()).toDays());
            double farDte = Math.max(nearDte + 1, Duration.between(today.atStartOfDay(), farExpiry.atStartOfDay()).toDays());
            double dfNear = Math.exp(-RISK_FREE * nearDte / 365.0);
            double dfFar = Math.exp(-RISK_FREE * farDte / 365.0);

            log.info("Jelly scan {}: ATM={} near={} far={} quotes={}", u, atm, nearExpiry, farExpiry, quotes.size());

            double bestPts = 0;
            for (int k : strikes) {
                OptionChainService.OptionQuote nCe = firstQuote(quotes, u, nearExpiry, k, "CE");
                OptionChainService.OptionQuote nPe = firstQuote(quotes, u, nearExpiry, k, "PE");
                OptionChainService.OptionQuote fCe = firstQuote(quotes, u, farExpiry, k, "CE");
                OptionChainService.OptionQuote fPe = firstQuote(quotes, u, farExpiry, k, "PE");
                if (nCe == null || nPe == null || fCe == null || fPe == null) continue;
                if (!hasBook(nCe) || !hasBook(nPe) || !hasBook(fCe) || !hasBook(fPe)) continue;
                if (spread(nCe) > MAX_OPTION_SPREAD || spread(nPe) > MAX_OPTION_SPREAD) continue;
                if (spread(fCe) > MAX_OPTION_SPREAD || spread(fPe) > MAX_OPTION_SPREAD) continue;

                // Executable synth: buy = CE.ask−PE.bid ; sell = CE.bid−PE.ask
                double nearBuy = nCe.ask - nPe.bid;
                double nearSell = nCe.bid - nPe.ask;
                double farBuy = fCe.ask - fPe.bid;
                double farSell = fCe.bid - fPe.ask;
                double nearMid = mid(nCe) - mid(nPe);
                double farMid = mid(fCe) - mid(fPe);

                double fairNear = dfNear * (fut - k);
                double fairFar = dfFar * (fut - k);
                double fairLongPay = fairFar - fairNear; // buy far − sell near

                // LONG jelly: buy far synth, sell near synth
                double longPay = farBuy - nearSell;
                double longEdgePts = fairLongPay - longPay;
                double longMidEdge = fairLongPay - (farMid - nearMid);
                if (longEdgePts > bestPts) bestPts = longEdgePts;
                if (longEdgePts >= 2.0 && longEdgePts <= MAX_EDGE_PTS
                        && longMidEdge >= longEdgePts * 0.35) {
                    double net = longEdgePts * lotSize - COSTS;
                    if (net >= MIN_EDGE_RS && net <= MAX_EDGE_RS) {
                        Map<String, Object> opp = buildOpp(u, k, spot, fut, nearExpiry, farExpiry,
                                nearDte, farDte, dfNear, dfFar, fairNear, fairFar,
                                longPay, fairLongPay, longEdgePts, net, lotSize, true,
                                nCe, nPe, fCe, fPe, nearBuy, nearSell, farBuy, farSell);
                        results.add(opp);
                        toSave.add(toArb(opp, nCe, nPe));
                    }
                }

                // SHORT jelly: sell far synth, buy near synth
                // net credit = farSell − nearBuy ; fair credit = fairFar − fairNear = fairLongPay
                double shortCredit = farSell - nearBuy;
                double shortEdgePts = shortCredit - fairLongPay;
                double shortMidEdge = (farMid - nearMid) - fairLongPay;
                if (shortEdgePts > bestPts) bestPts = shortEdgePts;
                if (shortEdgePts >= 2.0 && shortEdgePts <= MAX_EDGE_PTS
                        && shortMidEdge >= shortEdgePts * 0.35) {
                    double net = shortEdgePts * lotSize - COSTS;
                    if (net >= MIN_EDGE_RS && net <= MAX_EDGE_RS) {
                        Map<String, Object> opp = buildOpp(u, k, spot, fut, nearExpiry, farExpiry,
                                nearDte, farDte, dfNear, dfFar, fairNear, fairFar,
                                shortCredit, fairLongPay, shortEdgePts, net, lotSize, false,
                                nCe, nPe, fCe, fPe, nearBuy, nearSell, farBuy, farSell);
                        results.add(opp);
                        toSave.add(toArb(opp, nCe, nPe));
                    }
                }
            }

            if (!toSave.isEmpty()) {
                historyService.saveOpportunities(toSave, u, "JELLY_ROLL");
            }
            if (results.isEmpty()) {
                log.info("Jelly scan {} done: 0 opps (best near-miss {} pts)", u, String.format("%.1f", bestPts));
            } else {
                log.info("Jelly scan {} done: {} opportunities", u, results.size());
            }
        } catch (Exception e) {
            log.error("Jelly scan failed for {}: {}", u, e.getMessage(), e);
        }
        return results;
    }

    private Map<String, Object> buildOpp(String u, int k, double spot, double fut,
                                         LocalDate nearExpiry, LocalDate farExpiry,
                                         double nearDte, double farDte, double dfNear, double dfFar,
                                         double fairNear, double fairFar,
                                         double marketPay, double fairPay, double edgePts, double net,
                                         int lotSize, boolean isLong,
                                         OptionChainService.OptionQuote nCe, OptionChainService.OptionQuote nPe,
                                         OptionChainService.OptionQuote fCe, OptionChainService.OptionQuote fPe,
                                         double nearBuy, double nearSell, double farBuy, double farSell) {
        Map<String, Object> opp = new LinkedHashMap<>();
        opp.put("type", "JELLY_ROLL");
        opp.put("strategyType", "JELLY_ROLL");
        opp.put("underlying", u);
        opp.put("strike", k);
        opp.put("spotPrice", round2(spot));
        opp.put("futuresPrice", round2(fut));
        opp.put("nearExpiry", nearExpiry.toString());
        opp.put("farExpiry", farExpiry.toString());
        opp.put("expiryDate", farExpiry.toString());
        opp.put("daysNear", round2(nearDte));
        opp.put("daysFar", round2(farDte));
        opp.put("daysToExpiry", farDte);
        opp.put("dfNear", round4(dfNear));
        opp.put("dfFar", round4(dfFar));
        opp.put("fairNearSynth", round2(fairNear));
        opp.put("fairFarSynth", round2(fairFar));
        opp.put("marketPay", round2(marketPay));
        opp.put("fairPay", round2(fairPay));
        opp.put("nearBuySynth", round2(nearBuy));
        opp.put("nearSellSynth", round2(nearSell));
        opp.put("farBuySynth", round2(farBuy));
        opp.put("farSellSynth", round2(farSell));
        opp.put("edgePoints", round2(edgePts));
        opp.put("edgeAfterCosts", round2(net));
        opp.put("lotSize", lotSize);
        opp.put("ceBid", nCe.bid);
        opp.put("ceAsk", nCe.ask);
        opp.put("peBid", nPe.bid);
        opp.put("peAsk", nPe.ask);
        opp.put("guaranteedFill", false);
        opp.put("quality", net > 500 ? "REVIEW" : "OK");
        if (isLong) {
            opp.put("action", "LONG_JELLY");
            opp.put("legs", String.format(
                    "BUY %s %d CE @ %.1f | SELL %s %d PE @ %.1f | SELL %s %d CE @ %.1f | BUY %s %d PE @ %.1f",
                    farExpiry, k, fCe.ask, farExpiry, k, fPe.bid,
                    nearExpiry, k, nCe.bid, nearExpiry, k, nPe.ask));
            opp.put("description", "[JELLY] Long jelly — buy far synth / sell near synth vs DF·(F−K)");
        } else {
            opp.put("action", "SHORT_JELLY");
            opp.put("legs", String.format(
                    "SELL %s %d CE @ %.1f | BUY %s %d PE @ %.1f | BUY %s %d CE @ %.1f | SELL %s %d PE @ %.1f",
                    farExpiry, k, fCe.bid, farExpiry, k, fPe.ask,
                    nearExpiry, k, nCe.ask, nearExpiry, k, nPe.bid));
            opp.put("description", "[JELLY] Short jelly — sell far synth / buy near synth vs DF·(F−K)");
        }
        return opp;
    }

    private ArbitrageOpportunity toArb(Map<String, Object> m,
                                       OptionChainService.OptionQuote nCe,
                                       OptionChainService.OptionQuote nPe) {
        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.underlying = String.valueOf(m.get("underlying"));
        opp.strike = ((Number) m.get("strike")).intValue();
        opp.type = "JELLY_ROLL";
        opp.action = String.valueOf(m.get("action"));
        opp.legs = String.valueOf(m.get("legs"));
        opp.description = String.valueOf(m.get("description"));
        opp.spotPrice = ((Number) m.getOrDefault("spotPrice", 0)).doubleValue();
        opp.futuresPrice = ((Number) m.getOrDefault("futuresPrice", 0)).doubleValue();
        opp.cePrice = nCe.lastPrice;
        opp.pePrice = nPe.lastPrice;
        opp.ceBid = nCe.bid;
        opp.ceAsk = nCe.ask;
        opp.peBid = nPe.bid;
        opp.peAsk = nPe.ask;
        opp.edgePoints = ((Number) m.get("edgePoints")).doubleValue();
        opp.edgeAfterCosts = ((Number) m.get("edgeAfterCosts")).doubleValue();
        opp.confidence = 62;
        opp.daysToExpiry = ((Number) m.getOrDefault("daysToExpiry", 20)).doubleValue();
        try {
            opp.expiryDate = LocalDate.parse(String.valueOf(m.get("expiryDate")));
        } catch (Exception e) {
            opp.expiryDate = LocalDate.now().plusDays(20);
        }
        opp.detectedAt = LocalDateTime.now();
        Map<String, Double> costs = new LinkedHashMap<>();
        for (String k : List.of("fairNearSynth", "fairFarSynth", "marketPay", "fairPay",
                "nearBuySynth", "farBuySynth", "dfNear", "dfFar")) {
            if (m.get(k) instanceof Number n) costs.put(k, n.doubleValue());
        }
        costs.put("costsInr", COSTS);
        costs.put("netInr", opp.edgeAfterCosts);
        opp.costBreakdown = costs;
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

    private static boolean hasBook(OptionChainService.OptionQuote q) {
        return q.bid > 0 && q.ask > 0 && q.ask >= q.bid;
    }

    private static double spread(OptionChainService.OptionQuote q) {
        return q.ask - q.bid;
    }

    private static double mid(OptionChainService.OptionQuote q) {
        return (q.bid + q.ask) / 2.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static List<Map<String, Object>> copy(List<Map<String, Object>> src) {
        List<Map<String, Object>> out = new ArrayList<>(src.size());
        for (Map<String, Object> m : src) out.add(new LinkedHashMap<>(m));
        return out;
    }

    private record Cached(long at, List<Map<String, Object>> opps) {}
    private record CachedForward(long at, List<Map<String, Object>> rows) {}
}
