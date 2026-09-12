package com.stokr.smartstrategy;

import com.stokr.arbitrage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class BrokenWingButterflyScanner {

    private static final Logger log = LoggerFactory.getLogger(BrokenWingButterflyScanner.class);

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public BrokenWingButterflyScanner(OptionChainService optionChainService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scan(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY") : List.of(underlying);
        List<Map<String, Object>> results = new ArrayList<>();
        for (String u : targets) {
            try { results.addAll(scanForUnderlying(u)); } catch (Exception e) {
                log.error("BWB scan failed for {}: {}", u, e.getMessage());
            }
        }
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("creditReceived", 0)).doubleValue(),
            ((Number) a.getOrDefault("creditReceived", 0)).doubleValue()));
        return results;
    }

    private List<Map<String, Object>> scanForUnderlying(String underlying) {
        List<Map<String, Object>> results = new ArrayList<>();
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey,
            FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey));
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return results;

        LocalDate expiry = optionChainService.getNearestExpiry(underlying);
        long dte = Math.max(1, Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays());
        int step = OptionChainService.getStrikeStep(underlying);
        int lotSize = OptionChainService.getLotSize(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);

        List<String> instruments = new ArrayList<>();
        for (int i = -6; i <= 6; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
        log.info("BWB [{}]: spot={}, atm={}, expiry={}, dte={}, instruments={}, quotes={}",
            underlying, spot, atmStrike, expiry, dte, instruments.size(), quotes.size());

        // PUT BWB: Buy lower put, sell 2x middle put, buy higher put (skip a strike on downside)
        // CALL BWB: Buy higher call, sell 2x middle call, buy lower call (skip a strike on upside)
        for (String optType : List.of("PE", "CE")) {
            for (int bodyOffset = 1; bodyOffset <= 3; bodyOffset++) {
                for (int wingSkip = 1; wingSkip <= 2; wingSkip++) {
                    int bodyStrike, nearWingStrike, farWingStrike;
                    if ("PE".equals(optType)) {
                        bodyStrike = atmStrike - bodyOffset * step;
                        nearWingStrike = bodyStrike + step;
                        farWingStrike = bodyStrike - (1 + wingSkip) * step;
                    } else {
                        bodyStrike = atmStrike + bodyOffset * step;
                        nearWingStrike = bodyStrike - step;
                        farWingStrike = bodyStrike + (1 + wingSkip) * step;
                    }

                    OptionChainService.OptionQuote nearQ = getQuote(quotes, underlying, expiry, nearWingStrike, optType);
                    OptionChainService.OptionQuote bodyQ = getQuote(quotes, underlying, expiry, bodyStrike, optType);
                    OptionChainService.OptionQuote farQ = getQuote(quotes, underlying, expiry, farWingStrike, optType);
                    if (nearQ == null || bodyQ == null || farQ == null) continue;
                    if (nearQ.ask <= 0 || bodyQ.bid <= 0 || farQ.ask <= 0) continue;

                    double credit = 2 * bodyQ.bid - nearQ.ask - farQ.ask;
                    if (credit <= 0) continue;

                    int narrowWidth = Math.abs(nearWingStrike - bodyStrike);
                    int wideWidth = Math.abs(farWingStrike - bodyStrike);
                    double maxProfitNarrow = (narrowWidth + credit) * lotSize;
                    double maxLossWide = (wideWidth - credit) * lotSize;
                    double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 4 + 40;

                    if (maxLossWide <= 0 || maxProfitNarrow <= txnCost) continue;

                    Map<String, Object> opp = new LinkedHashMap<>();
                    opp.put("strategyType", "BROKEN_WING_BUTTERFLY");
                    opp.put("underlying", underlying);
                    opp.put("optionType", optType);
                    opp.put("nearWingStrike", nearWingStrike);
                    opp.put("bodyStrike", bodyStrike);
                    opp.put("farWingStrike", farWingStrike);
                    opp.put("expiry", expiry.toString());
                    opp.put("expiryDate", expiry.toString());
                    opp.put("dte", dte);
                    opp.put("lotSize", lotSize);
                    opp.put("spotPrice", round2(spot));
                    opp.put("nearWingPrice", round2(nearQ.ask));
                    opp.put("bodyPrice", round2(bodyQ.bid));
                    opp.put("farWingPrice", round2(farQ.ask));
                    opp.put("creditReceived", round2(credit));
                    opp.put("creditRs", round2(credit * lotSize));
                    opp.put("maxProfit", round2(maxProfitNarrow));
                    opp.put("maxLoss", round2(maxLossWide + txnCost));
                    opp.put("riskReward", round2(maxProfitNarrow / (maxLossWide + txnCost)));
                    opp.put("zeroRiskSide", "PE".equals(optType) ? "UPSIDE" : "DOWNSIDE");
                    opp.put("action", String.format("BUY %d%s @ %.1f | SELL 2x%d%s @ %.1f | BUY %d%s @ %.1f",
                        nearWingStrike, optType, nearQ.ask, bodyStrike, optType, bodyQ.bid, farWingStrike, optType, farQ.ask));
                    opp.put("legList", List.of(
                        Map.of("strike", nearWingStrike, "optionType", optType, "side", "BUY", "qty", 1, "price", nearQ.ask,
                            "symbol", getSymbol(quotes, underlying, expiry, nearWingStrike, optType)),
                        Map.of("strike", bodyStrike, "optionType", optType, "side", "SELL", "qty", 2, "price", bodyQ.bid,
                            "symbol", getSymbol(quotes, underlying, expiry, bodyStrike, optType)),
                        Map.of("strike", farWingStrike, "optionType", optType, "side", "BUY", "qty", 1, "price", farQ.ask,
                            "symbol", getSymbol(quotes, underlying, expiry, farWingStrike, optType))
                    ));
                    opp.put("edgePoints", round2(credit));
                    opp.put("edgeAfterCosts", round2(credit * lotSize - txnCost));
                    results.add(opp);
                }
            }
        }
        return results;
    }

    private OptionChainService.OptionQuote getQuote(Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, int strike, String optType) {
        for (String c : optionChainService.buildNfoSymbolCandidates(underlying, expiry, strike, optType)) {
            if (quotes.containsKey(c) && quotes.get(c).lastPrice > 0) return quotes.get(c);
        }
        return null;
    }

    private String getSymbol(Map<String, OptionChainService.OptionQuote> quotes,
            String underlying, LocalDate expiry, int strike, String optType) {
        for (String c : optionChainService.buildNfoSymbolCandidates(underlying, expiry, strike, optType)) {
            if (quotes.containsKey(c) && quotes.get(c).lastPrice > 0) return c;
        }
        return underlying + strike + optType;
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
