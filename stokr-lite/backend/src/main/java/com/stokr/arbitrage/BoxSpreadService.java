package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class BoxSpreadService {

    private static final Logger log = LoggerFactory.getLogger(BoxSpreadService.class);

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final ZerodhaSpotPriceFetcher spotPriceFetcher;

    private static final double MIN_BOX_EDGE_AFTER_COSTS = -1000.0;

    public BoxSpreadService(OptionChainService optionChainService,
                            OptionArbHistoryService historyService,
                            ZerodhaSpotPriceFetcher spotPriceFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.spotPriceFetcher = spotPriceFetcher;
    }

    public List<Map<String, Object>> scanBoxSpread(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying) 
            ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
            : List.of(underlying);

        List<Map<String, Object>> results = new ArrayList<>();

        Map<String, String> spotKeys = Map.of(
            "NIFTY", "NSE:NIFTY 50",
            "BANKNIFTY", "NSE:NIFTY BANK",
            "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
            "FINNIFTY", "NSE:NIFTY FIN SERVICE"
        );

        String yy = String.format("%02d", LocalDate.now().getYear() % 100);
        String mon = LocalDate.now().getMonth().name().substring(0, 3);

        Map<String, String> futKeys = Map.of(
            "NIFTY", "NFO:NIFTY" + yy + mon + "FUT",
            "BANKNIFTY", "NFO:BANKNIFTY" + yy + mon + "FUT",
            "MIDCPNIFTY", "NFO:MIDCPNIFTY" + yy + mon + "FUT",
            "FINNIFTY", "NFO:FINNIFTY" + yy + mon + "FUT"
        );

        for (String u : targets) {
            try {
                String spotKey = spotKeys.getOrDefault(u, "NSE:NIFTY 50");
                String futKey = futKeys.getOrDefault(u, "NFO:" + u + yy + mon + "FUT");

                double[] spotFut = spotPriceFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : spot;
                if (spot <= 0 && fut > 0) spot = fut;

                if (spot <= 0) continue;

                List<ArbitrageOpportunity> opps = scanBoxSpreadForUnderlying(u, spot, fut);
                if (opps != null && !opps.isEmpty()) {
                    historyService.saveOpportunities(opps, u, "BOX_SPREAD");

                    for (ArbitrageOpportunity opp : opps) {
                        Map<String, Object> map = opp.toMap();
                        map.put("strategyType", "BOX_SPREAD");
                        map.put("guaranteedFill", true);
                        map.put("boxEdgeInr", opp.edgeAfterCosts);
                        results.add(map);
                    }
                }
            } catch (Exception e) {
                log.error("Error scanning Box Spread for {}: {}", u, e.getMessage(), e);
            }
        }

        return results;
    }

    public List<ArbitrageOpportunity> scanBoxSpreadForUnderlying(String underlying, double spotPrice, double futuresPrice) {
        List<ArbitrageOpportunity> opps = new ArrayList<>();
        try {
            int step = OptionChainService.getStrikeStep(underlying);
            int atmStrike = (int) (Math.round(spotPrice / step) * step);

            List<Integer> strikes = new ArrayList<>();
            for (int i = -3; i <= 3; i++) {
                strikes.add(atmStrike + i * step);
            }

            LocalDate expiryDate = LocalDate.now();
            List<String> instruments = new ArrayList<>();

            for (int s : strikes) {
                String mon = expiryDate.getMonth().name().substring(0, 3);
                int yy = expiryDate.getYear() % 100;
                int month = expiryDate.getMonthValue();
                int day = expiryDate.getDayOfMonth();

                instruments.add(String.format("%s%02d%s%dCE", underlying, yy, mon, s));
                instruments.add(String.format("%s%02d%s%dPE", underlying, yy, mon, s));
                instruments.add(String.format("%s%02d%d%02d%dCE", underlying, yy, month, day, s));
                instruments.add(String.format("%s%02d%d%02d%dPE", underlying, yy, month, day, s));
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
            int lotSize = OptionChainService.getLotSize(underlying);

            for (int i = 0; i < strikes.size(); i++) {
                for (int j = i + 1; j < strikes.size(); j++) {
                    int k1 = strikes.get(i);
                    int k2 = strikes.get(j);
                    double width = k2 - k1;

                    String mon = expiryDate.getMonth().name().substring(0, 3);
                    int yy = expiryDate.getYear() % 100;
                    int month = expiryDate.getMonthValue();
                    int day = expiryDate.getDayOfMonth();

                    String ce1Key = String.format("%s%02d%s%dCE", underlying, yy, mon, k1);
                    String pe1Key = String.format("%s%02d%s%dPE", underlying, yy, mon, k1);
                    String ce2Key = String.format("%s%02d%s%dCE", underlying, yy, mon, k2);
                    String pe2Key = String.format("%s%02d%s%dPE", underlying, yy, mon, k2);

                    OptionChainService.OptionQuote ce1 = quotes.get(ce1Key);
                    OptionChainService.OptionQuote pe1 = quotes.get(pe1Key);
                    OptionChainService.OptionQuote ce2 = quotes.get(ce2Key);
                    OptionChainService.OptionQuote pe2 = quotes.get(pe2Key);

                    if (ce1 == null || pe1 == null || ce2 == null || pe2 == null) {
                        ce1Key = String.format("%s%02d%d%02d%dCE", underlying, yy, month, day, k1);
                        pe1Key = String.format("%s%02d%d%02d%dPE", underlying, yy, month, day, k1);
                        ce2Key = String.format("%s%02d%d%02d%dCE", underlying, yy, month, day, k2);
                        pe2Key = String.format("%s%02d%d%02d%dPE", underlying, yy, month, day, k2);

                        ce1 = quotes.get(ce1Key);
                        pe1 = quotes.get(pe1Key);
                        ce2 = quotes.get(ce2Key);
                        pe2 = quotes.get(pe2Key);
                    }

                    if (ce1 == null || pe1 == null || ce2 == null || pe2 == null) continue;

                    double ce1Ask = ce1.ask > 0 ? ce1.ask : ce1.lastPrice;
                    double pe1Bid = pe1.bid > 0 ? pe1.bid : pe1.lastPrice;
                    double ce2Bid = ce2.bid > 0 ? ce2.bid : ce2.lastPrice;
                    double pe2Ask = pe2.ask > 0 ? pe2.ask : pe2.lastPrice;

                    double buyBoxCost = ce1Ask - pe1Bid - ce2Bid + pe2Ask;
                    double buyBoxEdgePoints = width - buyBoxCost;
                    double grossBuyEdge = buyBoxEdgePoints * lotSize;
                    double buyCosts = 160.0;
                    double netBuyEdge = grossBuyEdge - buyCosts;

                    if (netBuyEdge >= MIN_BOX_EDGE_AFTER_COSTS) {
                        ArbitrageOpportunity opp = new ArbitrageOpportunity();
                        opp.underlying = underlying;
                        opp.strike = k1;
                        opp.type = "BOX_SPREAD";
                        opp.action = "LONG BOX (" + k1 + "/" + k2 + ")";
                        opp.spotPrice = spotPrice;
                        opp.futuresPrice = futuresPrice;
                        opp.cePrice = ce1.lastPrice;
                        opp.pePrice = pe1.lastPrice;
                        opp.ceBid = ce1.bid;
                        opp.ceAsk = ce1.ask;
                        opp.peBid = pe1.bid;
                        opp.peAsk = pe1.ask;
                        opp.edgePoints = Math.round(buyBoxEdgePoints * 10.0) / 10.0;
                        opp.edgeAfterCosts = Math.round(netBuyEdge * 10.0) / 10.0;
                        opp.confidence = 95.0;
                        opp.legs = String.format("BUY %d CE @ %.1f | SELL %d PE @ %.1f | SELL %d CE @ %.1f | BUY %d PE @ %.1f",
                            k1, ce1Ask, k1, pe1Bid, k2, ce2Bid, k2, pe2Ask);
                        opps.add(opp);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error calculating Box Spread for {}: {}", underlying, e.getMessage(), e);
        }
        return opps;
    }
}
