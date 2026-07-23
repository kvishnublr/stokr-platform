package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class BoxSpreadService {

    private static final Logger log = LoggerFactory.getLogger(BoxSpreadService.class);
    private static final double RISK_FREE_RATE = 0.065;
    private static final double MIN_BOX_PROFIT = 50.0;
    private static final double MAX_ENTRY_COST_PCT = 0.95;

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50",
        "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
        "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public BoxSpreadService(OptionChainService optionChainService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scanBoxSpread(String underlying) {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            String spotKey = SPOT_KEYS.get(underlying);
            if (spotKey == null) return results;
            double spot = spotFetcher.getSpotPrice(spotKey);
            if (spot <= 0) return results;

            int lotSize = OptionChainService.getLotSize(underlying);
            int atmStrike = optionChainService.getATMStrike(underlying, spot);
            List<Integer> strikes = optionChainService.generateStrikes(atmStrike, underlying);

            LocalDate expiry = optionChainService.getWeeklyExpiryDate(underlying);
            if (expiry == null) return results;
            double daysToExpiry = Duration.between(LocalDate.now().atStartOfDay(), expiry.atStartOfDay()).toDays();
            if (daysToExpiry < 1) return results;

            List<String> instruments = new ArrayList<>();
            for (int strike : strikes) {
                instruments.add(optionChainService.buildNfoSymbol(underlying, expiry, strike, "CE"));
                instruments.add(optionChainService.buildNfoSymbol(underlying, expiry, strike, "PE"));
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);
            if (quotes.isEmpty()) return results;

            for (int i = 0; i < strikes.size(); i++) {
                for (int j = i + 1; j < strikes.size(); j++) {
                    int k1 = strikes.get(i);
                    int k2 = strikes.get(j);
                    int width = k2 - k1;

                    String ceK1Key = optionChainService.buildNfoSymbol(underlying, expiry, k1, "CE");
                    String peK1Key = optionChainService.buildNfoSymbol(underlying, expiry, k1, "PE");
                    String ceK2Key = optionChainService.buildNfoSymbol(underlying, expiry, k2, "CE");
                    String peK2Key = optionChainService.buildNfoSymbol(underlying, expiry, k2, "PE");

                    OptionChainService.OptionQuote ceK1 = quotes.get(ceK1Key);
                    OptionChainService.OptionQuote peK1 = quotes.get(peK1Key);
                    OptionChainService.OptionQuote ceK2 = quotes.get(ceK2Key);
                    OptionChainService.OptionQuote peK2 = quotes.get(peK2Key);

                    if (ceK1 == null || peK1 == null || ceK2 == null || peK2 == null) continue;
                    if (ceK1.lastPrice <= 0 || peK1.lastPrice <= 0 || ceK2.lastPrice <= 0 || peK2.lastPrice <= 0) continue;

                    double synthK1 = ceK1.ask > 0 ? ceK1.ask : ceK1.lastPrice;
                    double synthK1_credit = peK1.bid > 0 ? peK1.bid : peK1.lastPrice;
                    double synthK2_credit = ceK2.bid > 0 ? ceK2.bid : ceK2.lastPrice;
                    double synthK2_debit = peK2.ask > 0 ? peK2.ask : peK2.lastPrice;

                    double entryCost = (synthK1 - synthK1_credit) + (synthK2_debit - synthK2_credit);

                    double guaranteedPayoff = width;
                    double grossProfit = guaranteedPayoff - entryCost;

                    double sellPremium = synthK1_credit + synthK2_credit;
                    double stt = sellPremium * lotSize * 0.001;
                    double brokerage = 120.0;
                    double exchange = sellPremium * lotSize * 0.0000345;
                    double sebi = sellPremium * lotSize * 0.000001;
                    double gst = (brokerage + sebi) * 0.18;
                    double ipft = sellPremium * lotSize * 0.0000001;
                    double totalCosts = stt + brokerage + exchange + sebi + gst + ipft;

                    double netProfitPerLot = (grossProfit * lotSize) - totalCosts;
                    double returnOnMargin = entryCost * lotSize > 0 ? netProfitPerLot / (entryCost * lotSize) * 100 : 0;

                    if (netProfitPerLot >= MIN_BOX_PROFIT && entryCost < guaranteedPayoff * MAX_ENTRY_COST_PCT) {
                        Map<String, Object> opp = new LinkedHashMap<>();
                        opp.put("underlying", underlying);
                        opp.put("type", "BOX_SPREAD");
                        opp.put("lowerStrike", k1);
                        opp.put("upperStrike", k2);
                        opp.put("width", width);
                        opp.put("lotSize", lotSize);
                        opp.put("expiry", expiry.toString());
                        opp.put("daysToExpiry", (int) daysToExpiry);

                        opp.put("ceK1Ask", ceK1.ask > 0 ? ceK1.ask : ceK1.lastPrice);
                        opp.put("peK1Bid", peK1.bid > 0 ? peK1.bid : peK1.lastPrice);
                        opp.put("ceK2Bid", ceK2.bid > 0 ? ceK2.bid : ceK2.lastPrice);
                        opp.put("peK2Ask", peK2.ask > 0 ? peK2.ask : peK2.lastPrice);

                        opp.put("entryCost", Math.round(entryCost * 100.0) / 100.0);
                        opp.put("guaranteedPayoff", guaranteedPayoff);
                        opp.put("grossProfitPts", Math.round(grossProfit * 100.0) / 100.0);
                        opp.put("totalCosts", Math.round(totalCosts));
                        opp.put("netProfitPerLot", Math.round(netProfitPerLot));
                        opp.put("returnOnMargin", Math.round(returnOnMargin * 100.0) / 100.0);

                        opp.put("legs", String.format(
                            "BUY CE %d + SELL PE %d + SELL CE %d + BUY PE %d", k1, k1, k2, k2));
                        opp.put("description", String.format(
                            "Box %d-%d: cost=%.1f payoff=%d net=Rs.%d (%.2f%% on margin)",
                            k1, k2, entryCost, guaranteedPayoff, Math.round(netProfitPerLot), returnOnMargin));

                        results.add(opp);
                    }
                }
            }

            results.sort((a, b) -> Double.compare(
                (double) b.get("netProfitPerLot"), (double) a.get("netProfitPerLot")));

        } catch (Exception e) {
            log.error("Box spread scan failed for {}: {}", underlying, e.getMessage());
        }

        return results;
    }

    public List<Map<String, Object>> scanAll() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (String u : List.of("NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY")) {
            all.addAll(scanBoxSpread(u));
        }
        return all;
    }
}
