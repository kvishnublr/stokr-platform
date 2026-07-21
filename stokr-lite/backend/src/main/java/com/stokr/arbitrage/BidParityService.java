package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class BidParityService {

    private static final Logger log = LoggerFactory.getLogger(BidParityService.class);

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final double RISK_FREE_RATE = 0.065;
    private static final double MIN_PARITY_DEVIATION_BID = 8.0;
    private static final double MIN_EDGE_AFTER_COSTS = 200.0;
    private static final int MIN_VOLUME = 100;
    private static final int MIN_OI = 100;

    private static final Map<String, double[]> DTE_RANGES = Map.of(
        "NIFTY",     new double[]{0, 21},
        "BANKNIFTY", new double[]{3, 21},
        "MIDCPNIFTY", new double[]{3, 21},
        "FINNIFTY",  new double[]{3, 21}
    );

    private static final Map<String, String> CONFIGS_SPOT = Map.of(
        "NIFTY",     "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY","NSE:NIFTY MID SELECT",
        "FINNIFTY",  "NSE:NIFTY FIN SERVICE"
    );

    private static final Map<String, String> CONFIGS_FUT = Map.of(
        "NIFTY",     "NFO:NIFTY",
        "BANKNIFTY", "NFO:BANKNIFTY",
        "MIDCPNIFTY","NFO:MIDCPNIFTY",
        "FINNIFTY",  "NFO:FINNIFTY"
    );

    public BidParityService(OptionChainService optionChainService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    private List<Integer> generateWideStrikes(int atmStrike, String underlying, int range) {
        List<Integer> strikes = new ArrayList<>();
        int step;
        switch (underlying) {
            case "BANKNIFTY": step = 100; break;
            case "MIDCPNIFTY": step = 50; break;
            case "FINNIFTY": step = 50; break;
            default: step = 50; break;
        }
        for (int i = -range; i <= range; i++) {
            strikes.add(atmStrike + i * step);
        }
        return strikes;
    }

    public List<Map<String, Object>> scanBidParity(String underlying) {
        List<Map<String, Object>> allOpps = new ArrayList<>();
        Set<String> underlyings = "ALL".equals(underlying)
            ? Set.of("NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY")
            : Set.of(underlying);

        for (String u : underlyings) {
            try {
                allOpps.addAll(scanBidParitySingle(u));
            } catch (Exception e) {
                log.error("Bid parity scan failed for {}: {}", u, e.getMessage());
            }
        }

        allOpps.sort((a, b) -> Double.compare(
            (double) b.getOrDefault("edgeAfterCosts", 0),
            (double) a.getOrDefault("edgeAfterCosts", 0)));

        return allOpps;
    }

    private List<Map<String, Object>> scanBidParitySingle(String underlying) {
        List<Map<String, Object>> opportunities = new ArrayList<>();

        String spotKey = CONFIGS_SPOT.get(underlying);
        String futPrefix = CONFIGS_FUT.get(underlying);
        if (spotKey == null) return opportunities;

        double spot = spotFetcher.getSpotPrice(spotKey);
        if (spot <= 0) {
            log.warn("No spot price for {}", underlying);
            return opportunities;
        }

        LocalDate expiryDate = optionChainService.getWeeklyExpiryDate(underlying);
        int yy = expiryDate.getYear() % 100;
        String mon = expiryDate.getMonth().name().substring(0, 3);
        String futKey = String.format("%s%02d%sFUT", futPrefix, yy, mon);
        double futuresPrice = spotFetcher.getSpotPrice(futKey);
        if (futuresPrice <= 0) futuresPrice = spot;

        int atmStrike = optionChainService.getATMStrike(underlying, spot);
        List<Integer> strikes = generateWideStrikes(atmStrike, underlying, 5);

        double daysToExpiry = Duration.between(LocalDate.now().atStartOfDay(), expiryDate.atStartOfDay()).toDays();
        double yearsToExpiry = daysToExpiry / 365.0;

        if (daysToExpiry < 0) return opportunities;

        double[] dteRange = DTE_RANGES.getOrDefault(underlying, new double[]{3, 21});
        if (daysToExpiry < dteRange[0] || daysToExpiry > dteRange[1]) return opportunities;

        List<String> instruments = new ArrayList<>();
        for (int strike : strikes) {
            instruments.add(optionChainService.buildNfoSymbol(underlying, expiryDate, strike, "CE"));
            instruments.add(optionChainService.buildNfoSymbol(underlying, expiryDate, strike, "PE"));
        }

        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
        int lotSize = OptionChainService.getLotSize(underlying);

        for (int strike : strikes) {
            String ceKey = optionChainService.buildNfoSymbol(underlying, expiryDate, strike, "CE");
            String peKey = optionChainService.buildNfoSymbol(underlying, expiryDate, strike, "PE");

            OptionChainService.OptionQuote ceQ = quotes.get(ceKey);
            OptionChainService.OptionQuote peQ = quotes.get(peKey);

            if (ceQ == null || peQ == null) continue;
            if (ceQ.lastPrice <= 0 || peQ.lastPrice <= 0) continue;
            if (ceQ.bid <= 0 || peQ.bid <= 0) continue;
            if (ceQ.volume < MIN_VOLUME || peQ.volume < MIN_VOLUME) continue;
            if (ceQ.openInterest < MIN_OI || peQ.openInterest < MIN_OI) continue;

            long ceBidQty = ceQ.bidQty;
            long peBidQty = peQ.bidQty;
            long ceAskQty = ceQ.askQty;
            long peAskQty = peQ.askQty;

            if (ceBidQty < lotSize || peBidQty < lotSize) continue;

            double ceBid = ceQ.bid;
            double peBid = peQ.bid;
            double ceAsk = ceQ.ask > 0 ? ceQ.ask : ceQ.lastPrice;
            double peAsk = peQ.ask > 0 ? peQ.ask : peQ.lastPrice;

            // Bid-price put-call parity:
            // Synthetic futures from BID prices = K + (ceBid - peBid) * e^(rT)
            // If synthetic > actual futures → REVERSAL: sell CE@bid, buy PE@ask, buy FUT
            //   (sell overpriced synthetic, buy cheap future)
            // If synthetic < actual futures → CONVERSION: buy CE@ask, sell PE@bid, sell FUT
            //   (buy cheap synthetic, sell expensive future)
            double syntheticBid = strike + (ceBid - peBid) * Math.exp(RISK_FREE_RATE * yearsToExpiry);
            double bidParityDev = syntheticBid - futuresPrice;

            // Only show if deviation exceeds minimum
            if (Math.abs(bidParityDev) < MIN_PARITY_DEVIATION_BID) continue;

            // Calculate edge using ACTUAL execution prices (ask for buying, bid for selling)
            double edgePoints;
            if (bidParityDev > 0) {
                // REVERSAL: sell CE @ bid, buy PE @ ask, buy FUT
                edgePoints = (strike + ceBid - peAsk) - futuresPrice;
            } else {
                // CONVERSION: buy CE @ ask, sell PE @ bid, sell FUT
                edgePoints = futuresPrice - (strike + ceAsk - peBid);
            }
            double grossEdge = edgePoints * lotSize;

            if (grossEdge <= 0) continue;

            // Transaction costs
            double stt = grossEdge * 0.001;
            double brokerage = 120.0;
            double exchange = grossEdge * 0.0000345;
            double sebi = grossEdge * 0.000001;
            double gst = (brokerage + sebi) * 0.18;
            double ipft = grossEdge * 0.0000001;
            double totalCosts = stt + brokerage + exchange + sebi + gst + ipft;
            double netEdge = grossEdge - totalCosts;

            if (netEdge < MIN_EDGE_AFTER_COSTS) continue;

            Map<String, Object> opp = new LinkedHashMap<>();
            opp.put("type", "BID_PARITY");
            opp.put("underlying", underlying);
            opp.put("strike", strike);
            opp.put("spotPrice", spot);
            opp.put("futuresPrice", futuresPrice);
            opp.put("daysToExpiry", daysToExpiry);
            opp.put("lotSize", lotSize);
            opp.put("detectedAt", java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).toString());

            opp.put("ceSymbol", ceKey);
            opp.put("peSymbol", peKey);
            opp.put("ceBid", ceBid);
            opp.put("ceAsk", ceAsk);
            opp.put("peBid", peBid);
            opp.put("peAsk", peAsk);
            opp.put("ceBidQty", ceBidQty);
            opp.put("ceAskQty", ceAskQty);
            opp.put("peBidQty", peBidQty);
            opp.put("peAskQty", peAskQty);
            opp.put("ceLastPrice", ceQ.lastPrice);
            opp.put("peLastPrice", peQ.lastPrice);

            opp.put("syntheticBid", Math.round(syntheticBid * 100.0) / 100.0);
            opp.put("bidParityDev", Math.round(bidParityDev * 100.0) / 100.0);

            if (bidParityDev > 0) {
                // REVERSAL: sell overpriced synthetic, buy cheap future
                opp.put("action", "REVERSAL");
                opp.put("legs", String.format(
                    "SELL %d CE @ %.1f (bid) | BUY %d PE @ %.1f (ask) | BUY %s FUT @ %.0f",
                    strike, ceBid, strike, peAsk, underlying, futuresPrice));
                opp.put("description", String.format(
                    "Bid parity: Synthetic from bids %.1f > Futures %.0f by %.1f pts. Sell CE+PE synthetic, buy FUT.",
                    syntheticBid, futuresPrice, bidParityDev));
            } else {
                // CONVERSION: buy cheap synthetic, sell expensive future
                opp.put("action", "CONVERSION");
                opp.put("legs", String.format(
                    "BUY %d CE @ %.1f (ask) | SELL %d PE @ %.1f (bid) | SELL %s FUT @ %.0f",
                    strike, ceAsk, strike, peBid, underlying, futuresPrice));
                opp.put("description", String.format(
                    "Bid parity: Synthetic from bids %.1f < Futures %.0f by %.1f pts. Buy CE+PE synthetic, sell FUT.",
                    syntheticBid, futuresPrice, Math.abs(bidParityDev)));
            }

            opp.put("edgePoints", Math.round(Math.abs(edgePoints) * 100.0) / 100.0);
            opp.put("edgeAfterCosts", Math.round(netEdge * 100.0) / 100.0);
            opp.put("grossEdge", Math.round(grossEdge * 100.0) / 100.0);
            opp.put("totalCosts", Math.round(totalCosts * 100.0) / 100.0);

            Map<String, Double> costBreakdown = new LinkedHashMap<>();
            costBreakdown.put("grossEdge", grossEdge);
            costBreakdown.put("stt", stt);
            costBreakdown.put("brokerage", brokerage);
            costBreakdown.put("exchange", exchange);
            costBreakdown.put("sebi", sebi);
            costBreakdown.put("gst", gst);
            costBreakdown.put("ipft", ipft);
            costBreakdown.put("totalCosts", totalCosts);
            costBreakdown.put("netEdge", netEdge);
            costBreakdown.put("lotSize", (double) lotSize);
            opp.put("costBreakdown", costBreakdown);

            opp.put("confidence", Math.min(95, 75 + Math.abs(bidParityDev) / 2));

            opportunities.add(opp);
        }

        log.info("Bid parity scan for {}: {} opportunities (ATM={}, expiry={}, DTE={})",
            underlying, opportunities.size(), atmStrike, expiryDate, (int) daysToExpiry);

        return opportunities;
    }
}
