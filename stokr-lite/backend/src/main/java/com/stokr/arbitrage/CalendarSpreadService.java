package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * Calendar Spread Scanner (Theta Harvest Model).
 * SELL near-week ATM option (high theta decay) + BUY next-week same-strike option.
 * Edge = differential theta between near and far legs.
 * Not an arbitrage -- a defined-risk theta income strategy.
 */
@Service
public class CalendarSpreadService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSpreadService.class);
    private static final double RISK_FREE_RATE = 0.065;
    private static final double MIN_NET_DEBIT = 0.5;    // minimum spread to bother
    private static final double MAX_NET_DEBIT_RATIO = 0.50; // max cost as fraction of near price

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
        "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public CalendarSpreadService(OptionChainService optionChainService,
                                  ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scanCalendarSpreads(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
            : List.of(underlying);

        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try {
                results.addAll(scanForUnderlying(u));
            } catch (Exception e) {
                log.error("Calendar spread scan failed for {}: {}", u, e.getMessage());
            }
        }
        // Sort by theta differential (highest first)
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("thetaDiff", 0)).doubleValue(),
            ((Number) a.getOrDefault("thetaDiff", 0)).doubleValue()));
        return results;
    }

    private List<Map<String, Object>> scanForUnderlying(String underlying) {
        List<Map<String, Object>> results = new ArrayList<>();

        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey);
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey, futKey);
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return results;

        LocalDate nearExpiry = optionChainService.getWeeklyExpiryDate(underlying);
        // Next week expiry = current expiry + 7 days, then find next expiry day
        LocalDate farExpiry = nearExpiry.plusWeeks(1);
        // Validate: far must be after near
        if (!farExpiry.isAfter(nearExpiry)) return results;

        long nearDte = Math.max(1, Duration.between(LocalDate.now().atStartOfDay(), nearExpiry.atStartOfDay()).toDays());
        long farDte = Math.max(nearDte + 1, Duration.between(LocalDate.now().atStartOfDay(), farExpiry.atStartOfDay()).toDays());
        double nearYears = nearDte / 365.0;
        double farYears = farDte / 365.0;

        int step = OptionChainService.getStrikeStep(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);
        int lotSize = OptionChainService.getLotSize(underlying);

        // Scan ATM +/- 3 strikes for both CE and PE
        List<Integer> strikes = new ArrayList<>();
        for (int i = -3; i <= 3; i++) strikes.add(atmStrike + i * step);

        List<String> instruments = new ArrayList<>();
        for (int strike : strikes) {
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, nearExpiry, strike, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, nearExpiry, strike, "PE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, farExpiry, strike, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, farExpiry, strike, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        for (int strike : strikes) {
            for (String optionType : List.of("CE", "PE")) {
                addCalendarCandidate(results, quotes, underlying, nearExpiry, farExpiry,
                    strike, optionType, spot, lotSize, nearDte, farDte, nearYears, farYears);
            }
        }
        return results;
    }

    private void addCalendarCandidate(List<Map<String, Object>> results,
                                       Map<String, OptionChainService.OptionQuote> quotes,
                                       String underlying, LocalDate nearExpiry, LocalDate farExpiry,
                                       int strike, String optionType, double spot, int lotSize,
                                       long nearDte, long farDte, double nearYears, double farYears) {
        OptionChainService.OptionQuote near = null;
        for (String c : optionChainService.buildNfoSymbolCandidates(underlying, nearExpiry, strike, optionType)) {
            if (quotes.containsKey(c)) { near = quotes.get(c); break; }
        }
        OptionChainService.OptionQuote far = null;
        for (String c : optionChainService.buildNfoSymbolCandidates(underlying, farExpiry, strike, optionType)) {
            if (quotes.containsKey(c)) { far = quotes.get(c); break; }
        }
        if (near == null || far == null) return;
        if (near.bid <= 0 || far.ask <= 0) return;

        boolean isCall = "CE".equals(optionType);

        // Calendar: SELL near (collect bid), BUY far (pay ask)
        double netDebit = far.ask - near.bid; // cost to enter
        if (netDebit < MIN_NET_DEBIT) return; // too cheap or credit (unusual)

        // Compute IV for both legs
        double nearMid = (near.bid + near.ask) / 2.0;
        double farMid = (far.bid + far.ask) / 2.0;
        if (nearMid <= 0 || farMid <= 0) return;

        double nearIV = BlackScholesCalculator.impliedVolatility(
            nearMid, spot, strike, nearYears, RISK_FREE_RATE, isCall, 0.01, 50);
        double farIV = BlackScholesCalculator.impliedVolatility(
            farMid, spot, strike, farYears, RISK_FREE_RATE, isCall, 0.01, 50);

        if (nearIV <= 0 || farIV <= 0) return;

        // Compute Greeks for both legs
        BlackScholesCalculator.Greeks nearGreeks = BlackScholesCalculator.callGreeks(
            spot, strike, nearYears, RISK_FREE_RATE, nearIV);
        BlackScholesCalculator.Greeks farGreeks = BlackScholesCalculator.callGreeks(
            spot, strike, farYears, RISK_FREE_RATE, farIV);

        // Net theta = short near theta (positive, collecting) - long far theta (negative, paying)
        // BlackScholes theta is negative for long options, so:
        // Selling near -> we GAIN |nearTheta| per day
        // Buying far  -> we LOSE |farTheta| per day
        double nearTheta = Math.abs(nearGreeks.theta); // per day earned
        double farTheta = Math.abs(farGreeks.theta);   // per day paid
        double thetaDiff = nearTheta - farTheta;       // net daily theta income per lot

        double thetaDiffRs = thetaDiff * lotSize;

        // Max loss = net debit paid (if near expires OTM and far also expires worthless)
        double maxLoss = netDebit * lotSize;
        // Transaction costs
        double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 4 + 30;
        double totalCost = maxLoss + txnCost;

        // IV differential (term structure)
        double ivDiff = (nearIV - farIV) * 100; // positive = near IV > far IV (favorable)

        // Skip if theta differential is negative (we'd lose money every day)
        if (thetaDiff <= 0) return;

        Map<String, Object> opp = new LinkedHashMap<>();
        opp.put("type", "CALENDAR_SPREAD");
        opp.put("strategyType", "CALENDAR_SPREAD");
        opp.put("underlying", underlying);
        opp.put("optionType", optionType);
        opp.put("strike", strike);
        opp.put("nearExpiry", nearExpiry.toString());
        opp.put("farExpiry", farExpiry.toString());
        opp.put("nearDte", nearDte);
        opp.put("farDte", farDte);
        opp.put("nearSymbol", nearKey);
        opp.put("farSymbol", farKey);
        opp.put("nearBid", round2(near.bid));
        opp.put("nearAsk", round2(near.ask));
        opp.put("farBid", round2(far.bid));
        opp.put("farAsk", round2(far.ask));
        opp.put("nearPrice", round2(nearMid));
        opp.put("farPrice", round2(farMid));
        opp.put("netDebit", round2(netDebit));
        opp.put("nearIV", round2(nearIV * 100));
        opp.put("farIV", round2(farIV * 100));
        opp.put("ivDiff", round2(ivDiff));
        opp.put("nearTheta", round2(nearTheta));
        opp.put("farTheta", round2(farTheta));
        opp.put("thetaDiff", round2(thetaDiff));
        opp.put("thetaDiffRs", round2(thetaDiffRs));
        opp.put("maxLoss", round2(totalCost));
        opp.put("lotSize", lotSize);
        opp.put("spotPrice", round2(spot));
        opp.put("action", String.format("SELL %s %d @ %.1f | BUY %s %d @ %.1f",
            nearKey, strike, near.bid, farKey, strike, far.ask));
        opp.put("legList", List.of(
            Map.of("strike", strike, "optionType", optionType, "side", "SELL", "qty", 1, "price", near.bid, "symbol", nearKey, "expiry", nearExpiry.toString()),
            Map.of("strike", strike, "optionType", optionType, "side", "BUY", "qty", 1, "price", far.ask, "symbol", farKey, "expiry", farExpiry.toString())
        ));
        opp.put("legs", String.format("SELL %s %d @ %.1f | BUY %s %d @ %.1f",
            nearKey, strike, near.bid, farKey, strike, far.ask));
        results.add(opp);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
