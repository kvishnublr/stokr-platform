package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class BidParityService {

    private static final Logger log = LoggerFactory.getLogger(BidParityService.class);

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    private static final double RISK_FREE_RATE = 0.065;
    private static final double MIN_PARITY_DEVIATION_BID = 1.5;
    private static final double MIN_EDGE_AFTER_COSTS = 50.0;
    private static final int MIN_VOLUME = 500;
    private static final int MIN_OI = 2000;

    private static final Map<String, double[]> DTE_RANGES = Map.of(
        "NIFTY", new double[]{0.0, 60.0},
        "BANKNIFTY", new double[]{0.0, 60.0},
        "FINNIFTY", new double[]{0.0, 60.0},
        "MIDCPNIFTY", new double[]{0.0, 60.0}
    );

    private static final Map<String, String> CONFIGS_SPOT = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
        "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    private static final Map<String, String> CONFIGS_FUT = Map.of(
        "NIFTY", "NFO:NIFTY",
        "BANKNIFTY", "NFO:BANKNIFTY",
        "MIDCPNIFTY", "NFO:NIFTYMIDCP",
        "FINNIFTY", "NFO:FINNIFTY"
    );

    public BidParityService(OptionChainService optionChainService,
                            OptionArbHistoryService historyService,
                            ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public List<Map<String, Object>> scanBidParity(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
            : List.of(underlying);

        List<Map<String, Object>> results = new ArrayList<>();

        for (String u : targets) {
            try {
                results.addAll(scanBidParitySingle(u));
            } catch (Exception e) {
                log.error("Bid parity scan failed for {}: {}", u, e.getMessage(), e);
            }
        }

        results.sort((a, b) -> Double.compare(
            (double) b.getOrDefault("edgeAfterCosts", 0.0),
            (double) a.getOrDefault("edgeAfterCosts", 0.0)));

        return results;
    }

    private List<Map<String, Object>> scanBidParitySingle(String underlying) {
        List<Map<String, Object>> results = new ArrayList<>();

        String spotKey = CONFIGS_SPOT.getOrDefault(underlying, "NSE:NIFTY 50");
        String futKey = CONFIGS_FUT.getOrDefault(underlying, "NFO:NIFTY");

        String resolvedFutKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotPriceFetcher, spotKey);
        if (resolvedFutKey != null && !resolvedFutKey.isBlank()) {
            futKey = resolvedFutKey;
        }

        double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : spot;

        if (spot <= 0 && fut > 0) spot = fut;
        if (fut <= 0 && spot > 0) fut = spot;

        if (spot <= 0 || fut <= 0) {
            log.info("Skipping {}: spot={} or fut={} invalid", underlying, spot, fut);
            return results;
        }

        log.info("Scanning Bid Parity for {}: spot={}, fut={}", underlying, spot, fut);

        LocalDate expiry = optionChainService.getWeeklyExpiryDate(underlying);
        double daysToExpiry = Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays();

        double[] dteRange = DTE_RANGES.getOrDefault(underlying, new double[]{0.0, 60.0});
        if (daysToExpiry < dteRange[0] || daysToExpiry > dteRange[1]) {
            log.info("Skipping {}: DTE={} outside range [{}, {}]", underlying, daysToExpiry, dteRange[0], dteRange[1]);
            return results;
        }

        double yearsToExpiry = Math.max(daysToExpiry, 0.5) / 365.0;

        int atmStrike = optionChainService.getATMStrike(underlying, spot);
        int step = OptionChainService.getStrikeStep(underlying);
        List<Integer> strikes = new ArrayList<>();
        for (int i = -10; i <= 10; i++) {
            strikes.add(atmStrike + i * step);
        }

        List<String> instruments = new ArrayList<>();
        for (int strike : strikes) {
            String ce = optionChainService.buildNfoSymbol(underlying, expiry, strike, "CE");
            String pe = optionChainService.buildNfoSymbol(underlying, expiry, strike, "PE");
            if (ce != null) instruments.add(ce);
            if (pe != null) instruments.add(pe);
        }

        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        int lotSize = OptionChainService.getLotSize(underlying);
        int foundOpps = 0;

        for (int strike : strikes) {
            String ceSym = optionChainService.buildNfoSymbol(underlying, expiry, strike, "CE");
            String peSym = optionChainService.buildNfoSymbol(underlying, expiry, strike, "PE");

            OptionChainService.OptionQuote ceQuote = (ceSym != null) ? quotes.get(ceSym) : null;
            OptionChainService.OptionQuote peQuote = (peSym != null) ? quotes.get(peSym) : null;

            if (ceQuote == null || peQuote == null) continue;
            if (ceQuote.lastPrice <= 0 || peQuote.lastPrice <= 0) continue;
            if (ceQuote.bid <= 0 || peQuote.bid <= 0) continue;
            if (ceQuote.ask <= 0 || peQuote.ask <= 0) continue;
            if (ceQuote.volume < MIN_VOLUME || peQuote.volume < MIN_VOLUME) continue;
            if (ceQuote.openInterest < MIN_OI || peQuote.openInterest < MIN_OI) continue;

            double synthetic1 = BlackScholesCalculator.syntheticFutures(
                ceQuote.ask, peQuote.bid, strike, RISK_FREE_RATE, yearsToExpiry);
            double parityDev1 = synthetic1 - fut;

            double synthetic2 = BlackScholesCalculator.syntheticFutures(
                ceQuote.bid, peQuote.ask, strike, RISK_FREE_RATE, yearsToExpiry);
            double parityDev2 = fut - synthetic2;

            boolean isConvergent = parityDev1 >= MIN_PARITY_DEVIATION_BID;
            boolean isReversal = parityDev2 >= MIN_PARITY_DEVIATION_BID;

            if (!isConvergent && !isReversal) continue;

            double edgePoints;
            String action;
            String legs;

            if (isConvergent) {
                edgePoints = parityDev1;
                action = "BUY CE+PE / SELL FUT";
                legs = String.format("BUY %d CE @ %.1f | BUY %d PE @ %.1f | SELL %s FUT @ %.1f",
                    strike, ceQuote.ask, strike, peQuote.bid, underlying, fut);
            } else {
                edgePoints = parityDev2;
                action = "BUY FUT / SELL CE+PE";
                legs = String.format("SELL %d CE @ %.1f | SELL %d PE @ %.1f | BUY %s FUT @ %.1f",
                    strike, ceQuote.bid, strike, peQuote.ask, underlying, fut);
            }

            double grossEdge = edgePoints * lotSize;
            double edgeAfterCosts = calculateEdgeCosts(edgePoints, underlying);

            if (edgeAfterCosts < MIN_EDGE_AFTER_COSTS) continue;

            foundOpps++;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("strategyType", "BID_PARITY");
            map.put("underlying", underlying);
            map.put("strike", strike);
            map.put("action", action);
            map.put("legs", legs);
            map.put("spotPrice", spot);
            map.put("futuresPrice", fut);
            map.put("ceBid", ceQuote.bid);
            map.put("ceAsk", ceQuote.ask);
            map.put("peBid", peQuote.bid);
            map.put("peAsk", peQuote.ask);
            map.put("ceVolume", ceQuote.volume);
            map.put("ceOI", ceQuote.openInterest);
            map.put("peVolume", peQuote.volume);
            map.put("peOI", peQuote.openInterest);
            map.put("edgePoints", Math.round(edgePoints * 100.0) / 100.0);
            map.put("edgeAfterCosts", Math.round(edgeAfterCosts * 100.0) / 100.0);
            map.put("grossEdge", Math.round(grossEdge * 100.0) / 100.0);
            map.put("daysToExpiry", daysToExpiry);
            map.put("lotSize", lotSize);
            map.put("confidence", Math.min(99.0, 70.0 + edgePoints * 1.5));
            map.put("guaranteedFill", true);
            map.put("bidEdgeInr", Math.round(edgeAfterCosts * 100.0) / 100.0);

            double stt = grossEdge * 0.001;
            double brokerage = 120.0;
            double exchange = grossEdge * 0.000345 * 6;
            double sebi = grossEdge * 0.00001;
            double gst = (brokerage + exchange + sebi) * 0.18;
            double ipft = grossEdge * 0.00001;
            double totalCosts = stt + brokerage + exchange + sebi + gst + ipft;

            Map<String, Double> costBreakdown = new LinkedHashMap<>();
            costBreakdown.put("grossEdge", Math.round(grossEdge * 100.0) / 100.0);
            costBreakdown.put("stt", Math.round(stt * 100.0) / 100.0);
            costBreakdown.put("brokerage", brokerage);
            costBreakdown.put("exchange", Math.round(exchange * 100.0) / 100.0);
            costBreakdown.put("sebi", Math.round(sebi * 100.0) / 100.0);
            costBreakdown.put("gst", Math.round(gst * 100.0) / 100.0);
            costBreakdown.put("ipft", Math.round(ipft * 100.0) / 100.0);
            costBreakdown.put("totalCosts", Math.round(totalCosts * 100.0) / 100.0);
            costBreakdown.put("netEdge", Math.round(edgeAfterCosts * 100.0) / 100.0);
            costBreakdown.put("lotSize", (double) lotSize);
            map.put("costBreakdown", costBreakdown);

            results.add(map);

            ArbitrageOpportunity opp = new ArbitrageOpportunity();
            opp.underlying = underlying;
            opp.type = "PARITY_BREAK";
            opp.strike = strike;
            opp.action = action;
            opp.legs = legs;
            opp.spotPrice = spot;
            opp.futuresPrice = fut;
            opp.cePrice = ceQuote.lastPrice;
            opp.pePrice = peQuote.lastPrice;
            opp.ceBid = ceQuote.bid;
            opp.ceAsk = ceQuote.ask;
            opp.peBid = peQuote.bid;
            opp.peAsk = peQuote.ask;
            opp.edgePoints = Math.round(edgePoints * 100.0) / 100.0;
            opp.edgeAfterCosts = Math.round(edgeAfterCosts * 100.0) / 100.0;
            opp.daysToExpiry = daysToExpiry;
            opp.confidence = Math.min(99.0, 70.0 + edgePoints * 1.5);
            opp.costBreakdown = costBreakdown;

            List<ArbitrageOpportunity> single = new ArrayList<>();
            single.add(opp);
            historyService.saveOpportunities(single, underlying, "BID_PARITY");
        }

        log.info("Bid parity scan for {}: {} opportunities (ATM={}, expiry={}, DTE={})",
            underlying, foundOpps, atmStrike, expiry, daysToExpiry);

        return results;
    }

    private double calculateEdgeCosts(double edgePoints, String underlying) {
        int lotSize = OptionChainService.getLotSize(underlying);
        double grossEdge = Math.abs(edgePoints) * lotSize;

        double stt = grossEdge * 0.001;
        double brokerage = 120.0;
        double exchange = grossEdge * 0.000345 * 6;
        double sebi = grossEdge * 0.00001;
        double gst = (brokerage + exchange + sebi) * 0.18;
        double ipft = grossEdge * 0.00001;
        double totalCosts = stt + brokerage + exchange + sebi + gst + ipft;

        return Math.max(0, grossEdge - totalCosts);
    }
}
