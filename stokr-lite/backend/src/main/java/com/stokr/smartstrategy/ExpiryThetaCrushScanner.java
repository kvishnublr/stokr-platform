package com.stokr.smartstrategy;

import com.stokr.arbitrage.*;
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
public class ExpiryThetaCrushScanner {

    private static final Logger log = LoggerFactory.getLogger(ExpiryThetaCrushScanner.class);

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
    );

    public ExpiryThetaCrushScanner(OptionChainService optionChainService, ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public List<Map<String, Object>> scan(String underlying) {
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY") : List.of(underlying);
        List<Map<String, Object>> results = new ArrayList<>();

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));

        for (String u : targets) {
            try {
                LocalDate expiry = optionChainService.getWeeklyExpiryDate(u);
                boolean isExpiryDay = today.equals(expiry);
                long dte = Duration.between(today.atStartOfDay(), expiry.atStartOfDay()).toDays();

                if (isExpiryDay) {
                    results.addAll(scanExpiryDay(u, nowIST));
                } else if (dte <= 2) {
                    results.addAll(scanNearExpiry(u, expiry, dte));
                }
            } catch (Exception e) {
                log.error("Theta crush scan failed for {}: {}", u, e.getMessage());
            }
        }
        results.sort((a, b) -> Double.compare(
            ((Number) b.getOrDefault("thetaDecayExpected", 0)).doubleValue(),
            ((Number) a.getOrDefault("thetaDecayExpected", 0)).doubleValue()));
        return results;
    }

    private List<Map<String, Object>> scanExpiryDay(String underlying, LocalTime nowIST) {
        List<Map<String, Object>> results = new ArrayList<>();
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey,
            FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey));
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return results;

        LocalDate expiry = optionChainService.getWeeklyExpiryDate(underlying);
        int step = OptionChainService.getStrikeStep(underlying);
        int lotSize = OptionChainService.getLotSize(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);

        List<String> instruments = new ArrayList<>();
        for (int i = -4; i <= 4; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        boolean isOptimalWindow = nowIST.isAfter(LocalTime.of(13, 30));
        String window = isOptimalWindow ? "OPTIMAL (post 1:30 PM)" : "EARLY (pre 1:30 PM)";

        for (int offset = 0; offset <= 1; offset++) {
            int ceStrike = atmStrike + offset * step;
            int peStrike = atmStrike - offset * step;
            int wingWidth = 2;
            int ceWingStrike = ceStrike + wingWidth * step;
            int peWingStrike = peStrike - wingWidth * step;

            OptionChainService.OptionQuote ceQ = getQuote(quotes, underlying, expiry, ceStrike, "CE");
            OptionChainService.OptionQuote peQ = getQuote(quotes, underlying, expiry, peStrike, "PE");
            OptionChainService.OptionQuote ceWingQ = getQuote(quotes, underlying, expiry, ceWingStrike, "CE");
            OptionChainService.OptionQuote peWingQ = getQuote(quotes, underlying, expiry, peWingStrike, "PE");
            if (ceQ == null || peQ == null || ceWingQ == null || peWingQ == null) continue;

            double straddleCredit = ceQ.bid + peQ.bid;
            double wingCost = ceWingQ.ask + peWingQ.ask;
            double netCredit = straddleCredit - wingCost;
            if (netCredit <= 0) continue;

            double minutesToClose = java.time.Duration.between(nowIST, LocalTime.of(15, 30)).toMinutes();
            if (minutesToClose <= 0) minutesToClose = 1;
            double decayRate = isOptimalWindow ? 0.7 : 0.4;
            double expectedDecay = netCredit * decayRate;

            double maxLoss = (wingWidth * step - netCredit) * lotSize;
            double expectedProfit = expectedDecay * lotSize;
            double txnCost = ArbitrageCosts.PER_LEG_BROKERAGE * 4 + 40;

            Map<String, Object> opp = new LinkedHashMap<>();
            opp.put("strategyType", "EXPIRY_THETA_CRUSH");
            opp.put("underlying", underlying);
            opp.put("ceStrike", ceStrike);
            opp.put("peStrike", peStrike);
            opp.put("ceWingStrike", ceWingStrike);
            opp.put("peWingStrike", peWingStrike);
            opp.put("expiry", expiry.toString());
            opp.put("expiryDate", expiry.toString());
            opp.put("dte", 0);
            opp.put("lotSize", lotSize);
            opp.put("spotPrice", round2(spot));
            opp.put("straddleCredit", round2(straddleCredit));
            opp.put("wingCost", round2(wingCost));
            opp.put("netCredit", round2(netCredit));
            opp.put("netCreditRs", round2(netCredit * lotSize));
            opp.put("thetaDecayExpected", round2(expectedDecay));
            opp.put("expectedProfitRs", round2(expectedProfit - txnCost));
            opp.put("maxLoss", round2(maxLoss + txnCost));
            opp.put("minutesToClose", (int) minutesToClose);
            opp.put("window", window);
            opp.put("isOptimalWindow", isOptimalWindow);
            opp.put("winRate", isOptimalWindow ? "90-95%" : "75-85%");
            opp.put("action", String.format("SELL %dCE @ %.1f + SELL %dPE @ %.1f | BUY %dCE @ %.1f + BUY %dPE @ %.1f",
                ceStrike, ceQ.bid, peStrike, peQ.bid, ceWingStrike, ceWingQ != null ? ceWingQ.ask : 0, peWingStrike, peWingQ != null ? peWingQ.ask : 0));
            opp.put("legList", List.of(
                Map.of("strike", ceStrike, "optionType", "CE", "side", "SELL", "qty", 1, "price", ceQ.bid,
                    "symbol", getSymbol(quotes, underlying, expiry, ceStrike, "CE")),
                Map.of("strike", peStrike, "optionType", "PE", "side", "SELL", "qty", 1, "price", peQ.bid,
                    "symbol", getSymbol(quotes, underlying, expiry, peStrike, "PE")),
                Map.of("strike", ceWingStrike, "optionType", "CE", "side", "BUY", "qty", 1, "price", ceWingQ != null ? ceWingQ.ask : 0,
                    "symbol", getSymbol(quotes, underlying, expiry, ceWingStrike, "CE")),
                Map.of("strike", peWingStrike, "optionType", "PE", "side", "BUY", "qty", 1, "price", peWingQ != null ? peWingQ.ask : 0,
                    "symbol", getSymbol(quotes, underlying, expiry, peWingStrike, "PE"))
            ));
            opp.put("edgePoints", round2(expectedDecay));
            opp.put("edgeAfterCosts", round2(expectedProfit - txnCost));
            results.add(opp);
        }
        return results;
    }

    private List<Map<String, Object>> scanNearExpiry(String underlying, LocalDate expiry, long dte) {
        List<Map<String, Object>> results = new ArrayList<>();
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey,
            FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey));
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return results;

        int step = OptionChainService.getStrikeStep(underlying);
        int lotSize = OptionChainService.getLotSize(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);

        List<String> instruments = new ArrayList<>();
        for (int i = -3; i <= 3; i++) {
            int s = atmStrike + i * step;
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "CE"));
            instruments.addAll(optionChainService.buildNfoSymbolCandidates(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        OptionChainService.OptionQuote ceQ = getQuote(quotes, underlying, expiry, atmStrike, "CE");
        OptionChainService.OptionQuote peQ = getQuote(quotes, underlying, expiry, atmStrike, "PE");
        if (ceQ == null || peQ == null) return results;

        double straddleValue = ceQ.bid + peQ.bid;
        double dailyDecay = straddleValue / (dte + 0.5);

        Map<String, Object> opp = new LinkedHashMap<>();
        opp.put("strategyType", "EXPIRY_THETA_CRUSH");
        opp.put("subType", "PRE_EXPIRY_SETUP");
        opp.put("underlying", underlying);
        opp.put("ceStrike", atmStrike);
        opp.put("peStrike", atmStrike);
        opp.put("expiry", expiry.toString());
        opp.put("expiryDate", expiry.toString());
        opp.put("dte", dte);
        opp.put("lotSize", lotSize);
        opp.put("spotPrice", round2(spot));
        opp.put("straddleValue", round2(straddleValue));
        opp.put("dailyThetaDecay", round2(dailyDecay));
        opp.put("dailyDecayRs", round2(dailyDecay * lotSize));
        opp.put("thetaDecayExpected", round2(dailyDecay));
        opp.put("window", dte + " days to expiry -- PREVIEW ONLY");
        opp.put("isOptimalWindow", false);
        opp.put("action", "PREVIEW: ATM straddle " + atmStrike + " @ " + round2(straddleValue) + " -- enter on expiry day");
        opp.put("edgePoints", round2(dailyDecay));
        opp.put("edgeAfterCosts", round2(dailyDecay * lotSize - 80));
        results.add(opp);
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
