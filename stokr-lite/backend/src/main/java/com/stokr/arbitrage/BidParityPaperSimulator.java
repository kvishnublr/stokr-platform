package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Paper-fill simulator for Bid Parity using the NEW executable parity logic.
 * Conservative = fill at bid/ask (matches live scanner). Mid / slip scenarios for range.
 * Capital-constrained projections for daily & monthly expectancy.
 */
@Service
public class BidParityPaperSimulator {

    private static final Logger log = LoggerFactory.getLogger(BidParityPaperSimulator.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double RISK_FREE = 0.065;
    private static final double BROKERAGE_3LEG = 120.0;

    private final BidParityService bidParityService;
    private final OptionArbHistoryService historyService;
    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    public BidParityPaperSimulator(BidParityService bidParityService,
                                   OptionArbHistoryService historyService,
                                   OptionChainService optionChainService,
                                   ZerodhaSpotPriceFetcher spotFetcher) {
        this.bidParityService = bidParityService;
        this.historyService = historyService;
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public Map<String, Object> run(String underlying,
                                   double minEdge,
                                   double capitalInr,
                                   int maxTradesPerDay,
                                   int lookbackDays,
                                   double fillRate) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", System.currentTimeMillis());
        out.put("underlying", underlying);
        out.put("minEdge", minEdge);
        out.put("capitalInr", capitalInr);
        out.put("maxTradesPerDay", maxTradesPerDay);
        out.put("lookbackDays", lookbackDays);
        out.put("fillRate", fillRate);
        out.put("assumptions", List.of(
                "Conservative fill = buy ask / sell bid (same as live scanner)",
                "Mid fill = mid of CE/PE quotes (optimistic)",
                "Slip +1pt = conservative edge minus 1 index point × lot",
                "Capital gate uses hedged margin estimate (NIFTY≈₹86k / lot)",
                "Legacy junk rows (bid/ask=0 or spot==fut with no book) are excluded",
                "Monthly ≈ avg daily × 20 trading days × fillRate"
        ));

        List<SimTrade> trades = new ArrayList<>();
        String source;

        LocalTime now = LocalTime.now(IST);
        boolean marketOpen = !now.isBefore(LocalTime.of(9, 15)) && !now.isAfter(LocalTime.of(15, 30))
                && !Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).contains(LocalDate.now(IST).getDayOfWeek());

        if (marketOpen) {
            source = "LIVE_SCAN";
            List<Map<String, Object>> opps = bidParityService.scanBidParity(underlying);
            for (Map<String, Object> m : opps) {
                SimTrade t = fromLiveMap(m);
                if (t != null && t.conservativeNet >= minEdge) trades.add(t);
            }
        } else {
            source = "HISTORY_FILTERED_PLUS_REPRICE";
            trades.addAll(fromHistory(underlying, lookbackDays, minEdge));
            // Also try a fresh chain scan off-hours (last quotes) for current expectancy
            try {
                List<SimTrade> fresh = fromOffHoursScan(underlying, minEdge);
                out.put("offHoursScanCount", fresh.size());
                // Prefer fresh for "today expectancy"; keep history for daily series
                out.put("freshSignals", fresh.stream().map(SimTrade::toMap).limit(20).toList());
            } catch (Exception e) {
                log.warn("Off-hours scan failed: {}", e.getMessage());
                out.put("offHoursScanError", e.getMessage());
            }
        }

        out.put("source", source);
        out.put("rawSignalCount", trades.size());

        // Deduplicate by day+underlying+strike+action (keep best conservative)
        Map<String, SimTrade> best = new LinkedHashMap<>();
        for (SimTrade t : trades) {
            String k = t.day + "|" + t.underlying + "|" + t.strike + "|" + t.action;
            SimTrade prev = best.get(k);
            if (prev == null || t.conservativeNet > prev.conservativeNet) best.put(k, t);
        }
        List<SimTrade> unique = new ArrayList<>(best.values());
        unique.sort((a, b) -> Double.compare(b.conservativeNet, a.conservativeNet));

        // Capital-constrained daily selection
        Map<LocalDate, List<SimTrade>> byDay = unique.stream()
                .collect(Collectors.groupingBy(t -> t.day, TreeMap::new, Collectors.toList()));

        List<Map<String, Object>> daily = new ArrayList<>();
        double sumCons = 0, sumMid = 0, sumSlip = 0;
        int sumTrades = 0;
        int daysWithTrades = 0;

        for (Map.Entry<LocalDate, List<SimTrade>> e : byDay.entrySet()) {
            List<SimTrade> dayTrades = e.getValue().stream()
                    .sorted((a, b) -> Double.compare(b.conservativeNet, a.conservativeNet))
                    .toList();
            List<SimTrade> taken = selectUnderCapital(dayTrades, capitalInr, maxTradesPerDay);
            double dCons = taken.stream().mapToDouble(t -> t.conservativeNet).sum();
            double dMid = taken.stream().mapToDouble(t -> t.midNet).sum();
            double dSlip = taken.stream().mapToDouble(t -> t.slipNet).sum();
            if (!taken.isEmpty()) daysWithTrades++;
            sumCons += dCons;
            sumMid += dMid;
            sumSlip += dSlip;
            sumTrades += taken.size();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", e.getKey().toString());
            row.put("signals", dayTrades.size());
            row.put("taken", taken.size());
            row.put("conservativePnl", round1(dCons));
            row.put("midPnl", round1(dMid));
            row.put("slipPnl", round1(dSlip));
            row.put("trades", taken.stream().map(SimTrade::toMap).toList());
            daily.add(row);
        }

        int dayCount = Math.max(1, byDay.size());
        double avgCons = sumCons / dayCount;
        double avgMid = sumMid / dayCount;
        double avgSlip = sumSlip / dayCount;
        double avgTrades = (double) sumTrades / dayCount;
        double fr = Math.max(0, Math.min(1, fillRate));

        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("tradingDaysInSample", byDay.size());
        projection.put("daysWithTrades", daysWithTrades);
        projection.put("avgTradesPerDay", round2(avgTrades));
        projection.put("avgDailyConservative", round1(avgCons));
        projection.put("avgDailyMid", round1(avgMid));
        projection.put("avgDailySlip", round1(avgSlip));
        projection.put("expectedDailyAtFillRate", round1(avgCons * fr));
        projection.put("expectedMonthlyConservative", round1(avgCons * 20 * fr));
        projection.put("expectedMonthlyMid", round1(avgMid * 20 * fr));
        projection.put("expectedMonthlySlip", round1(avgSlip * 20 * fr));
        projection.put("monthlyTradeEstimate", round1(avgTrades * 20 * fr));
        projection.put("note", byDay.isEmpty()
                ? "No clean executable signals in sample — wait for market hours with new scanner."
                : "Projections apply fillRate to capital-constrained selections only.");

        out.put("daily", daily);
        out.put("projection", projection);
        out.put("topSignals", unique.stream().limit(25).map(SimTrade::toMap).toList());
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        return out;
    }

    private List<SimTrade> selectUnderCapital(List<SimTrade> dayTrades, double capital, int maxTrades) {
        List<SimTrade> taken = new ArrayList<>();
        double used = 0;
        for (SimTrade t : dayTrades) {
            if (taken.size() >= maxTrades) break;
            double need = hedgedMargin(t.underlying);
            if (used + need > capital * 0.85) continue;
            taken.add(t);
            used += need;
        }
        return taken;
    }

    private static double hedgedMargin(String u) {
        if (u == null) return 90000;
        return switch (u.toUpperCase(Locale.ROOT)) {
            case "BANKNIFTY" -> 120000 * 1.15;
            case "FINNIFTY", "MIDCPNIFTY" -> 90000 * 1.15;
            default -> 75000 * 1.15;
        };
    }

    private List<SimTrade> fromHistory(String underlying, int lookbackDays, double minEdge) {
        LocalDate end = LocalDate.now(IST);
        LocalDate start = end.minusDays(Math.max(1, lookbackDays) - 1L);
        List<OptionArbOpportunity> all = historyService.getRepository()
                .findByScanTimeBetween(start.atStartOfDay(), end.atTime(LocalTime.MAX));
        List<SimTrade> out = new ArrayList<>();
        for (OptionArbOpportunity o : all) {
            if (o.getStrategyType() == null || !o.getStrategyType().toUpperCase(Locale.ROOT).contains("BID")) continue;
            if (!"ALL".equalsIgnoreCase(underlying) && !underlying.equalsIgnoreCase(o.getUnderlying())) continue;
            if (!isCleanQuote(o)) continue;
            String action = OptionArbAutoExecService.normalizeAction(o.getAction());
            if (action == null) continue;
            SimTrade t = reprice(o.getUnderlying(), o.getStrike(), action,
                    nz(o.getSpotPrice()), nz(o.getFuturesPrice()),
                    nz(o.getCeBid()), nz(o.getCeAsk()), nz(o.getPeBid()), nz(o.getPeAsk()),
                    o.getExpiryDate(),
                    o.getScanTime() != null ? o.getScanTime().toLocalDate() : end);
            if (t != null && t.conservativeNet >= minEdge) out.add(t);
        }
        return out;
    }

    private boolean isCleanQuote(OptionArbOpportunity o) {
        if (o.getCeBid() == null || o.getCeAsk() == null || o.getPeBid() == null || o.getPeAsk() == null) return false;
        if (o.getCeBid().doubleValue() <= 0 || o.getCeAsk().doubleValue() <= 0) return false;
        if (o.getPeBid().doubleValue() <= 0 || o.getPeAsk().doubleValue() <= 0) return false;
        if (o.getFuturesPrice() == null || o.getFuturesPrice().doubleValue() <= 0) return false;
        // Reject classic junk: spot forced equal fut AND zero edge book was common in old scanner
        // but keep if bid/ask present (new scanner may still have spot==fut for ATM-only fallback)
        return o.getCeAsk().doubleValue() >= o.getCeBid().doubleValue()
                && o.getPeAsk().doubleValue() >= o.getPeBid().doubleValue();
    }

    private List<SimTrade> fromOffHoursScan(String underlying, double minEdge) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
                ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
                : List.of(underlying);
        List<SimTrade> out = new ArrayList<>();
        Map<String, String> spotKeys = Map.of(
                "NIFTY", "NSE:NIFTY 50",
                "BANKNIFTY", "NSE:NIFTY BANK",
                "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
                "FINNIFTY", "NSE:NIFTY FIN SERVICE"
        );
        for (String u : targets) {
            try {
                String spotKey = spotKeys.getOrDefault(u, "NSE:NIFTY 50");
                String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotFetcher, spotKey);
                double[] sf = spotFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = sf != null && sf.length > 0 ? sf[0] : 0;
                double fut = sf != null && sf.length > 1 ? sf[1] : 0;
                if (fut <= 0) continue;
                if (spot <= 0) spot = fut;
                LocalDate expiry = BidParityService.resolveFuturesExpiry(u, futKey);
                List<ArbitrageOpportunity> opps = optionChainService.scanBidParityChain(u, spot, fut, expiry);
                for (ArbitrageOpportunity opp : opps) {
                    SimTrade t = fromArb(opp);
                    if (t != null && t.conservativeNet >= minEdge) out.add(t);
                }
            } catch (Exception e) {
                log.warn("Off-hours sim scan {} failed: {}", u, e.getMessage());
            }
        }
        return out;
    }

    private SimTrade fromLiveMap(Map<String, Object> m) {
        try {
            String action = OptionArbAutoExecService.normalizeAction(str(m.get("action")));
            if (action == null) return null;
            return reprice(str(m.get("underlying")),
                    (int) num(m.get("strike")),
                    action,
                    num(m.get("spotPrice")),
                    num(m.get("futuresPrice")),
                    num(m.get("ceBid")),
                    num(m.get("ceAsk")),
                    num(m.get("peBid")),
                    num(m.get("peAsk")),
                    m.get("expiryDate") != null ? LocalDate.parse(String.valueOf(m.get("expiryDate"))) : null,
                    LocalDate.now(IST));
        } catch (Exception e) {
            return null;
        }
    }

    private SimTrade fromArb(ArbitrageOpportunity opp) {
        String action = OptionArbAutoExecService.normalizeAction(opp.action);
        if (action == null) return null;
        return reprice(opp.underlying, (int) opp.strike, action,
                opp.spotPrice, opp.futuresPrice, opp.ceBid, opp.ceAsk, opp.peBid, opp.peAsk,
                opp.expiryDate, LocalDate.now(IST));
    }

    private SimTrade reprice(String underlying, int strike, String action,
                             double spot, double fut,
                             double ceBid, double ceAsk, double peBid, double peAsk,
                             LocalDate expiry, LocalDate day) {
        if (fut <= 0 || ceBid <= 0 || ceAsk <= 0 || peBid <= 0 || peAsk <= 0) return null;
        if (expiry == null) expiry = optionChainService.getMonthlyExpiry(underlying);
        double years = Math.max(0.5, Duration.between(LocalDate.now(IST).atStartOfDay(), expiry.atStartOfDay()).toDays()) / 365.0;
        double dfK = strike * Math.exp(-RISK_FREE * years);
        int lot = OptionChainService.getLotSize(underlying);

        // Conservative executable
        double consPts;
        if ("CONVERSION".equals(action)) {
            double synthBuy = ceAsk - peBid + dfK;
            consPts = fut - synthBuy;
        } else {
            double synthSell = ceBid - peAsk + dfK;
            consPts = synthSell - fut;
        }
        if (consPts < 0) consPts = 0;

        double ceMid = (ceBid + ceAsk) / 2.0;
        double peMid = (peBid + peAsk) / 2.0;
        double midPts;
        if ("CONVERSION".equals(action)) {
            midPts = Math.max(0, fut - (ceMid - peMid + dfK));
        } else {
            midPts = Math.max(0, (ceMid - peMid + dfK) - fut);
        }

        double consNet = netInr(consPts, lot);
        double midNet = netInr(midPts, lot);
        double slipNet = netInr(Math.max(0, consPts - 1.0), lot); // +1pt adverse

        SimTrade t = new SimTrade();
        t.day = day;
        t.underlying = underlying;
        t.strike = strike;
        t.action = action;
        t.expiry = expiry;
        t.futures = fut;
        t.spot = spot;
        t.ceBid = ceBid; t.ceAsk = ceAsk; t.peBid = peBid; t.peAsk = peAsk;
        t.conservativePts = round1(consPts);
        t.midPts = round1(midPts);
        t.conservativeNet = round1(consNet);
        t.midNet = round1(midNet);
        t.slipNet = round1(slipNet);
        t.lotSize = lot;
        t.marginEst = round1(hedgedMargin(underlying));
        return t;
    }

    private static double netInr(double pts, int lot) {
        double gross = pts * lot;
        double stt = gross * 0.001;
        double exchange = gross * 0.000345;
        double sebi = gross * 0.000001;
        double gst = (BROKERAGE_3LEG + exchange) * 0.18;
        return Math.max(0, gross - stt - BROKERAGE_3LEG - exchange - sebi - gst);
    }

    private static double nz(BigDecimal b) { return b == null ? 0 : b.doubleValue(); }
    private static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    static class SimTrade {
        LocalDate day;
        String underlying;
        int strike;
        String action;
        LocalDate expiry;
        double spot, futures;
        double ceBid, ceAsk, peBid, peAsk;
        double conservativePts, midPts;
        double conservativeNet, midNet, slipNet;
        int lotSize;
        double marginEst;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day != null ? day.toString() : null);
            m.put("underlying", underlying);
            m.put("strike", strike);
            m.put("action", action);
            m.put("expiryDate", expiry != null ? expiry.toString() : null);
            m.put("spot", spot);
            m.put("futures", futures);
            m.put("ceBid", ceBid); m.put("ceAsk", ceAsk);
            m.put("peBid", peBid); m.put("peAsk", peAsk);
            m.put("conservativePts", conservativePts);
            m.put("midPts", midPts);
            m.put("conservativeNet", conservativeNet);
            m.put("midNet", midNet);
            m.put("slipNet", slipNet);
            m.put("lotSize", lotSize);
            m.put("marginEst", marginEst);
            return m;
        }
    }
}
