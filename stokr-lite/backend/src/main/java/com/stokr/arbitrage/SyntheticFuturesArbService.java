package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class SyntheticFuturesArbService {

    private static final Logger log = LoggerFactory.getLogger(SyntheticFuturesArbService.class);
    private static final double MIN_EDGE_RS = 200.0; // minimum edge in rupees per lot

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
        "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public SyntheticFuturesArbService(OptionChainService optionChainService,
                                       ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scanSyntheticArb(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
            : List.of(underlying);

        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try {
                results.addAll(scanForUnderlying(u));
            } catch (Exception e) {
                log.error("Synthetic arb scan failed for {}: {}", u, e.getMessage());
            }
        }
        results.sort((a, b) -> Double.compare(
            Math.abs(((Number) b.getOrDefault("edgeRs", 0)).doubleValue()),
            Math.abs(((Number) a.getOrDefault("edgeRs", 0)).doubleValue())));
        return results;
    }

    private List<Map<String, Object>> scanForUnderlying(String underlying) {
        List<Map<String, Object>> results = new ArrayList<>();
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey);
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey, futKey);
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : spot;
        if (spot <= 0 || fut <= 0) return results;

        int step = OptionChainService.getStrikeStep(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);
        int lotSize = OptionChainService.getLotSize(underlying);
        LocalDate expiry = optionChainService.getWeeklyExpiryDate(underlying);

        // Scan ATM +/- 5 strikes
        List<Integer> strikes = new ArrayList<>();
        for (int i = -5; i <= 5; i++) strikes.add(atmStrike + i * step);

        List<String> instruments = new ArrayList<>();
        for (int strike : strikes) {
            instruments.add(optionChainService.buildNfoSymbol(underlying, expiry, strike, "CE"));
            instruments.add(optionChainService.buildNfoSymbol(underlying, expiry, strike, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        double yearsToExpiry = Math.max(
            Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays(), 0.5) / 365.0;

        for (int strike : strikes) {
            String ceKey = optionChainService.buildNfoSymbol(underlying, expiry, strike, "CE");
            String peKey = optionChainService.buildNfoSymbol(underlying, expiry, strike, "PE");
            OptionChainService.OptionQuote ce = quotes.get(ceKey);
            OptionChainService.OptionQuote pe = quotes.get(peKey);
            if (ce == null || pe == null) continue;
            if (ce.bid <= 0 || ce.ask <= 0 || pe.bid <= 0 || pe.ask <= 0) continue;

            // Synthetic Long Futures = Buy CE + Sell PE at same strike
            // Cost = CE_ask - PE_bid + Strike
            double synthLong = strike + ce.ask - pe.bid;
            // Synthetic Short Futures = Sell CE + Buy PE at same strike
            // Proceeds = CE_bid - PE_ask + Strike
            double synthShort = strike + ce.bid - pe.ask;

            // Compare with actual futures
            double longEdge = fut - synthLong;  // positive = buy synthetic, sell real futures
            double shortEdge = synthShort - fut; // positive = sell synthetic, buy real futures

            // Transaction costs estimate (4 legs: CE + PE + FUT entry + exit)
            double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 6 + 50; // ~170

            double longEdgeRs = longEdge * lotSize - txnCost;
            double shortEdgeRs = shortEdge * lotSize - txnCost;

            if (longEdgeRs > MIN_EDGE_RS) {
                Map<String, Object> opp = new LinkedHashMap<>();
                opp.put("type", "SYNTHETIC_FUTURES_ARB");
                opp.put("underlying", underlying);
                opp.put("strike", strike);
                opp.put("direction", "BUY_SYNTHETIC");
                opp.put("action", String.format("BUY CE %d @ %.1f + SELL PE %d @ %.1f + SELL FUT @ %.1f",
                    strike, ce.ask, strike, pe.bid, fut));
                opp.put("synthPrice", round2(synthLong));
                opp.put("futPrice", round2(fut));
                opp.put("edgePoints", round2(longEdge));
                opp.put("edgeRs", round2(longEdgeRs));
                opp.put("txnCost", round2(txnCost));
                opp.put("lotSize", lotSize);
                opp.put("ceBid", ce.bid); opp.put("ceAsk", ce.ask);
                opp.put("peBid", pe.bid); opp.put("peAsk", pe.ask);
                opp.put("nearExpiry", expiry.toString());
                opp.put("daysToExpiry", Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays());
                opp.put("confidence", Math.min(99, 80 + longEdge * 2));
                opp.put("legList", List.of(
                    Map.of("strike", strike, "optionType", "CE", "side", "BUY", "qty", 1, "price", ce.ask),
                    Map.of("strike", strike, "optionType", "PE", "side", "SELL", "qty", 1, "price", pe.bid),
                    Map.of("strike", 0, "optionType", "FUT", "side", "SELL", "qty", 1, "price", fut)
                ));
                results.add(opp);
            }

            if (shortEdgeRs > MIN_EDGE_RS) {
                Map<String, Object> opp = new LinkedHashMap<>();
                opp.put("type", "SYNTHETIC_FUTURES_ARB");
                opp.put("underlying", underlying);
                opp.put("strike", strike);
                opp.put("direction", "SELL_SYNTHETIC");
                opp.put("action", String.format("SELL CE %d @ %.1f + BUY PE %d @ %.1f + BUY FUT @ %.1f",
                    strike, ce.bid, strike, pe.ask, fut));
                opp.put("synthPrice", round2(synthShort));
                opp.put("futPrice", round2(fut));
                opp.put("edgePoints", round2(shortEdge));
                opp.put("edgeRs", round2(shortEdgeRs));
                opp.put("txnCost", round2(txnCost));
                opp.put("lotSize", lotSize);
                opp.put("ceBid", ce.bid); opp.put("ceAsk", ce.ask);
                opp.put("peBid", pe.bid); opp.put("peAsk", pe.ask);
                opp.put("nearExpiry", expiry.toString());
                opp.put("daysToExpiry", Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays());
                opp.put("confidence", Math.min(99, 80 + shortEdge * 2));
                opp.put("legList", List.of(
                    Map.of("strike", strike, "optionType", "CE", "side", "SELL", "qty", 1, "price", ce.bid),
                    Map.of("strike", strike, "optionType", "PE", "side", "BUY", "qty", 1, "price", pe.ask),
                    Map.of("strike", 0, "optionType", "FUT", "side", "BUY", "qty", 1, "price", fut)
                ));
                results.add(opp);
            }
        }
        return results;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
