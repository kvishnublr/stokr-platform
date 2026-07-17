package com.stokr.arbitrage;

import com.stokr.external.ZerodhaTokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OptionChainService {

    private static final Logger log = LoggerFactory.getLogger(OptionChainService.class);

    private final ZerodhaTokenManager tokenManager;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${zerodha.api-key:zazlrld244cc6jf0}")
    private String apiKey;

    private static final double RISK_FREE_RATE = 0.065;
    private static final double MIN_PARITY_DEVIATION = 15.0;
    private static final double MIN_EDGE_AFTER_COSTS = 300.0;
    private static final double MAX_SPREAD_PCT = 5.0;
    private static final int COOLDOWN_SECONDS = 60;

    private final ConcurrentHashMap<String, Long> cooldownMap = new ConcurrentHashMap<>();

    public OptionChainService(ZerodhaTokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public List<ArbitrageOpportunity> scanOptionChain(String underlying, double spotPrice, double futuresPrice) {
        List<ArbitrageOpportunity> opportunities = new ArrayList<>();

        try {
            int atmStrike = getATMStrike(underlying, spotPrice);
            List<Integer> strikes = generateStrikes(atmStrike, underlying);

            LocalDate expiryDate = getExpiryDate(underlying);
            double daysToExpiry = Duration.between(LocalDate.now().atStartOfDay(), expiryDate.atStartOfDay()).toDays();
            double yearsToExpiry = daysToExpiry / 365.0;

            if (daysToExpiry <= 0) {
                log.warn("No future expiry found for {}, skipping scan", underlying);
                return opportunities;
            }

            List<String> instruments = new ArrayList<>();
            Map<String, Integer> instrumentStrikeMap = new HashMap<>();
            Map<String, String> instrumentTypeMap = new HashMap<>();

            for (int strike : strikes) {
                String ceSymbol = buildNfoSymbol(underlying, expiryDate, strike, "CE");
                String peSymbol = buildNfoSymbol(underlying, expiryDate, strike, "PE");
                instruments.add(ceSymbol);
                instruments.add(peSymbol);
                instrumentStrikeMap.put(ceSymbol, strike);
                instrumentStrikeMap.put(peSymbol, strike);
                instrumentTypeMap.put(ceSymbol, "CE");
                instrumentTypeMap.put(peSymbol, "PE");
            }

            log.info("Fetching quotes for {} instruments (ATM={}, expiry={}, DTE={})",
                instruments.size(), atmStrike, expiryDate, (int) daysToExpiry);

            Map<String, OptionQuote> quotes = fetchQuotes(instruments);

            log.info("Got {}/{} quotes for {}", quotes.size(), instruments.size(), underlying);

            int validStrikes = 0;
            for (int strike : strikes) {
                String ceKey = buildNfoSymbol(underlying, expiryDate, strike, "CE");
                String peKey = buildNfoSymbol(underlying, expiryDate, strike, "PE");

                OptionQuote ceQuote = quotes.get(ceKey);
                OptionQuote peQuote = quotes.get(peKey);

                if (ceQuote == null || peQuote == null) continue;
                if (ceQuote.lastPrice <= 0 || peQuote.lastPrice <= 0) continue;
                if (isSpreadTooWide(ceQuote) || isSpreadTooWide(peQuote)) continue;

                validStrikes++;

                // 1. Put-Call Parity
                double parityDev = BlackScholesCalculator.parityDeviation(
                    ceQuote.lastPrice, peQuote.lastPrice, strike, RISK_FREE_RATE, yearsToExpiry, futuresPrice);

                if (Math.abs(parityDev) >= MIN_PARITY_DEVIATION) {
                    String cooldownKey = underlying + "_" + strike + "_PARITY";
                    if (!isOnCooldown(cooldownKey)) {
                        double edgeAfterCosts = calculateParityEdge(parityDev, underlying, ceQuote.lastPrice, peQuote.lastPrice, futuresPrice);
                        if (edgeAfterCosts >= MIN_EDGE_AFTER_COSTS) {
                            opportunities.add(buildParityOpportunity(
                                underlying, strike, ceQuote, peQuote, parityDev,
                                edgeAfterCosts, daysToExpiry, spotPrice, futuresPrice));
                            cooldownMap.put(cooldownKey, System.currentTimeMillis());
                        }
                    }
                }

                // 2. IV Spike
                double ceIV = BlackScholesCalculator.impliedVolatility(
                    ceQuote.lastPrice, spotPrice, strike, yearsToExpiry, RISK_FREE_RATE, true, 0.01, 100);
                double peIV = BlackScholesCalculator.impliedVolatility(
                    peQuote.lastPrice, spotPrice, strike, yearsToExpiry, RISK_FREE_RATE, false, 0.01, 100);

                double avgIV = (ceIV + peIV) / 2.0;
                double estimatedRV = estimateRealizedVol(spotPrice);
                double ivPremium = (avgIV - estimatedRV) / estimatedRV * 100;

                if (ivPremium > 30) {
                    String cooldownKey = underlying + "_" + strike + "_IV";
                    if (!isOnCooldown(cooldownKey)) {
                        opportunities.add(buildIvSpikeOpportunity(
                            underlying, strike, ceQuote, peQuote, avgIV, estimatedRV,
                            ivPremium, daysToExpiry, spotPrice));
                        cooldownMap.put(cooldownKey, System.currentTimeMillis());
                    }
                }

                // 3. Deep ITM Stale Quote
                if (strike < spotPrice * 0.95) {
                    if (peQuote.lastPrice > 0 && peQuote.openInterest > 0) {
                        double intrinsicValue = spotPrice - strike;
                        double marketPremium = peQuote.lastPrice - intrinsicValue;
                        if (marketPremium < -50) {
                            String cooldownKey = underlying + "_" + strike + "_DEEP";
                            if (!isOnCooldown(cooldownKey)) {
                                opportunities.add(buildDeepItmOpportunity(
                                    underlying, strike, peQuote, intrinsicValue, daysToExpiry, spotPrice));
                                cooldownMap.put(cooldownKey, System.currentTimeMillis());
                            }
                        }
                    }
                }

                // 4. Skew Anomaly
                if (peIV > ceIV * 1.15 && peIV > 0.20) {
                    String cooldownKey = underlying + "_" + strike + "_SKEW";
                    if (!isOnCooldown(cooldownKey)) {
                        opportunities.add(buildSkewOpportunity(
                            underlying, strike, ceQuote, peQuote, ceIV, peIV, daysToExpiry, spotPrice, futuresPrice));
                        cooldownMap.put(cooldownKey, System.currentTimeMillis());
                    }
                }
            }

            log.info("Analyzed {} valid strikes for {}, found {} opportunities", validStrikes, underlying, opportunities.size());

        } catch (Exception e) {
            log.error("Error scanning option chain for {}: {}", underlying, e.getMessage(), e);
        }

        return opportunities;
    }

    public Map<String, OptionQuote> fetchQuotes(List<String> instruments) {
        Map<String, OptionQuote> result = new HashMap<>();
        String token = getAuthToken();
        if (token == null) {
            log.warn("No auth token available for quote fetch");
            return result;
        }

        for (int i = 0; i < instruments.size(); i += 50) {
            List<String> batch = instruments.subList(i, Math.min(i + 50, instruments.size()));
            String url = buildQuoteUrl(batch);

            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "token " + apiKey + ":" + token);
                headers.set("X-Kite-Version", "3");

                ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

                if (response.getBody() != null && response.getBody().containsKey("data")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
                    for (Map.Entry<String, Object> entry : data.entrySet()) {
                        String instrument = entry.getKey();
                        if (!(entry.getValue() instanceof Map)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> quoteData = (Map<String, Object>) entry.getValue();

                        OptionQuote quote = new OptionQuote();
                        quote.instrument = instrument;
                        quote.lastPrice = getDouble(quoteData, "last_price");
                        quote.openInterest = getLong(quoteData, "oi");
                        quote.volume = getLong(quoteData, "volume");
                        quote.bid = getDepthPrice(quoteData, "buy");
                        quote.ask = getDepthPrice(quoteData, "sell");

                        String lookupKey = instrument.startsWith("NFO:") ? instrument.substring(4) : instrument;
                        result.put(lookupKey, quote);
                    }
                }

                Thread.sleep(350);

            } catch (Exception e) {
                log.warn("Failed to fetch quotes batch {}: {}", i, e.getMessage());
            }
        }

        return result;
    }

    public String getAuthToken() {
        try {
            var auth = tokenManager.getCurrentAuth();
            if (auth != null && auth.getAccessToken() != null) {
                return auth.getAccessToken();
            }
        } catch (Exception e) {
            log.warn("Failed to get token from manager: {}", e.getMessage());
        }
        return null;
    }

    private String buildQuoteUrl(List<String> instruments) {
        StringBuilder sb = new StringBuilder("https://api.kite.trade/quote?");
        for (int i = 0; i < instruments.size(); i++) {
            if (i > 0) sb.append("&");
            sb.append("i=NFO:").append(instruments.get(i));
        }
        return sb.toString();
    }

    private int getATMStrike(String underlying, double spotPrice) {
        return switch (underlying) {
            case "NIFTY", "NIFTY 50" -> (int) Math.round(spotPrice / 50.0) * 50;
            case "BANKNIFTY", "NIFTY BANK" -> (int) Math.round(spotPrice / 100.0) * 100;
            case "MIDCPNIFTY" -> (int) Math.round(spotPrice / 50.0) * 50;
            case "FINNIFTY" -> (int) Math.round(spotPrice / 50.0) * 50;
            default -> (int) Math.round(spotPrice / 50.0) * 50;
        };
    }

    private List<Integer> generateStrikes(int atmStrike, String underlying) {
        List<Integer> strikes = new ArrayList<>();
        int step = "BANKNIFTY".equals(underlying) ? 100 : 50;
        for (int i = -10; i <= 10; i++) {
            strikes.add(atmStrike + i * step);
        }
        return strikes;
    }

    private LocalDate getExpiryDate(String underlying) {
        if ("MIDCPNIFTY".equals(underlying) || "FINNIFTY".equals(underlying)) {
            return getMonthlyExpiryDate();
        }
        return getWeeklyExpiryDate(underlying);
    }

    private LocalDate getWeeklyExpiryDate(String underlying) {
        LocalDate today = LocalDate.now();
        LocalDate nextExpiry = today;

        while (nextExpiry.getDayOfWeek() != DayOfWeek.TUESDAY) {
            nextExpiry = nextExpiry.plusDays(1);
        }

        if (nextExpiry.equals(today)) {
            java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
            if (nowIST.isAfter(java.time.LocalTime.of(15, 0))) {
                nextExpiry = nextExpiry.plusWeeks(1);
            }
        }

        return nextExpiry;
    }

    private LocalDate getMonthlyExpiryDate() {
        LocalDate today = LocalDate.now();
        LocalDate candidate = today.with(java.time.DayOfWeek.TUESDAY);

        // Find the last Tuesday of the month
        while (candidate.plusWeeks(1).getMonth() == today.getMonth()) {
            candidate = candidate.plusWeeks(1);
        }

        if (candidate.isBefore(today)) {
            candidate = candidate.plusMonths(1);
            while (candidate.getDayOfWeek() != DayOfWeek.TUESDAY) {
                candidate = candidate.plusDays(1);
            }
            while (candidate.plusWeeks(1).getMonth() == candidate.getMonth()) {
                candidate = candidate.plusWeeks(1);
            }
        }

        if (candidate.equals(today)) {
            java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
            if (nowIST.isAfter(java.time.LocalTime.of(15, 0))) {
                candidate = candidate.plusMonths(1);
                while (candidate.getDayOfWeek() != DayOfWeek.TUESDAY) {
                    candidate = candidate.plusDays(1);
                }
                while (candidate.plusWeeks(1).getMonth() == candidate.getMonth()) {
                    candidate = candidate.plusWeeks(1);
                }
            }
        }

        return candidate;
    }

    public LocalDate getWeeklyExpiry() {
        return getWeeklyExpiryDate("NIFTY");
    }

    public LocalDate getMonthlyExpiry() {
        return getMonthlyExpiryDate();
    }

    private String buildNfoSymbol(String underlying, LocalDate expiryDate, int strike, String type) {
        String cleanUnderlying = underlying.replace(" ", "");
        int yy = expiryDate.getYear() % 100;
        int month = expiryDate.getMonthValue();
        int day = expiryDate.getDayOfMonth();
        return String.format("%s%02d%d%02d%d%s", cleanUnderlying, yy, month, day, strike, type);
    }

    private boolean isSpreadTooWide(OptionQuote quote) {
        if (quote.bid <= 0 || quote.ask <= 0) return false;
        double spreadPct = (quote.ask - quote.bid) / quote.ask * 100;
        return spreadPct > MAX_SPREAD_PCT;
    }

    private boolean isOnCooldown(String key) {
        Long lastAlert = cooldownMap.get(key);
        if (lastAlert == null) return false;
        return (System.currentTimeMillis() - lastAlert) < COOLDOWN_SECONDS * 1000L;
    }

    public static int getLotSize(String underlying) {
        return switch (underlying) {
            case "NIFTY", "NIFTY 50" -> 65;
            case "BANKNIFTY", "NIFTY BANK" -> 30;
            case "MIDCPNIFTY" -> 120;
            case "FINNIFTY" -> 60;
            default -> 65;
        };
    }

    private double calculateParityEdge(double parityDev, String underlying, double cePrice, double pePrice, double futuresPrice) {
        double rawEdge = Math.abs(parityDev);
        int lotSize = getLotSize(underlying);
        double grossEdge = rawEdge * lotSize;

        double sttRate = 0.001;
        double sttFutRate = 0.0002;
        double brokeragePerOrder = 20;
        double exchangePct = 0.0000345;
        double sebiPct = 0.000001;

        double sttEntry = pePrice * sttRate * lotSize + futuresPrice * sttFutRate * lotSize;
        double sttExit = cePrice * sttRate * lotSize + futuresPrice * sttFutRate * lotSize;
        double totalSTT = sttEntry + sttExit;

        double totalBrokerage = brokeragePerOrder * 6;

        double turnover = (cePrice + pePrice + futuresPrice) * lotSize * 2;
        double totalExchange = turnover * exchangePct;
        double totalSebi = turnover * sebiPct;
        double totalGst = (totalBrokerage + totalExchange) * 0.18;

        double totalCosts = totalSTT + totalBrokerage + totalExchange + totalSebi + totalGst;
        return grossEdge - totalCosts;
    }

    private double estimateRealizedVol(double spotPrice) {
        return 0.17;
    }

    private ArbitrageOpportunity buildParityOpportunity(String underlying, int strike,
            OptionQuote ceQuote, OptionQuote peQuote, double parityDev,
            double edgeAfterCosts, double daysToExpiry, double spotPrice, double futuresPrice) {

        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.type = "PARITY_BREAK";
        opp.underlying = underlying;
        opp.strike = strike;
        opp.edgePoints = Math.abs(parityDev);
        opp.edgeAfterCosts = edgeAfterCosts;
        opp.daysToExpiry = daysToExpiry;
        opp.spotPrice = spotPrice;
        opp.futuresPrice = futuresPrice;
        opp.cePrice = ceQuote.lastPrice;
        opp.pePrice = peQuote.lastPrice;
        opp.ceBid = ceQuote.bid;
        opp.ceAsk = ceQuote.ask;
        opp.peBid = peQuote.bid;
        opp.peAsk = peQuote.ask;
        opp.confidence = Math.min(95, 70 + Math.abs(parityDev) / 2);

        if (parityDev > 0) {
            opp.action = "REVERSAL";
            opp.legs = String.format(
                "SELL %d CE @ %.1f | BUY %d PE @ %.1f | BUY %s FUT @ %.0f",
                strike, ceQuote.lastPrice, strike, peQuote.lastPrice, underlying, futuresPrice);
        } else {
            opp.action = "CONVERSION";
            opp.legs = String.format(
                "BUY %d CE @ %.1f | SELL %d PE @ %.1f | SELL %s FUT @ %.0f",
                strike, ceQuote.lastPrice, strike, peQuote.lastPrice, underlying, futuresPrice);
        }

        opp.description = String.format(
            "Parity break: Synthetic %.1f vs Futures %.0f | Deviation %.1f pts | Edge Rs.%.0f after costs",
            BlackScholesCalculator.syntheticFutures(ceQuote.lastPrice, peQuote.lastPrice, strike, RISK_FREE_RATE, daysToExpiry/365),
            futuresPrice, parityDev, edgeAfterCosts);

        return opp;
    }

    private ArbitrageOpportunity buildIvSpikeOpportunity(String underlying, int strike,
            OptionQuote ceQuote, OptionQuote peQuote, double avgIV, double rv,
            double ivPremium, double daysToExpiry, double spotPrice) {

        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.type = "IV_SPIKE";
        opp.underlying = underlying;
        opp.strike = strike;
        opp.edgePoints = (avgIV - rv) * 100;
        opp.daysToExpiry = daysToExpiry;
        opp.spotPrice = spotPrice;
        opp.cePrice = ceQuote.lastPrice;
        opp.pePrice = peQuote.lastPrice;
        opp.confidence = Math.min(80, 60 + ivPremium / 5);
        opp.action = "SELL_STRADDLE";
        opp.legs = String.format(
            "SELL %d CE @ %.1f | SELL %d PE @ %.1f | IV %.1f%% vs RV %.1f%%",
            strike, ceQuote.lastPrice, strike, peQuote.lastPrice, avgIV * 100, rv * 100);
        opp.description = String.format(
            "IV spike: %.0f%% premium over RV. Sell premium, expect mean reversion",
            ivPremium);

        return opp;
    }

    private ArbitrageOpportunity buildDeepItmOpportunity(String underlying, int strike,
            OptionQuote peQuote, double intrinsicValue, double daysToExpiry, double spotPrice) {

        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.type = "DEEP_ITM_STALE";
        opp.underlying = underlying;
        opp.strike = strike;
        opp.edgePoints = intrinsicValue - peQuote.lastPrice;
        opp.edgeAfterCosts = opp.edgePoints * 15 - 80;
        opp.daysToExpiry = daysToExpiry;
        opp.spotPrice = spotPrice;
        opp.pePrice = peQuote.lastPrice;
        opp.confidence = 85;
        opp.action = "BUY_DEEP_ITM";
        opp.legs = String.format(
            "BUY %d PE @ %.1f (intrinsic %.1f) + SELL FUT",
            strike, peQuote.lastPrice, intrinsicValue);
        opp.description = String.format(
            "Deep ITM PE below intrinsic: Market %.1f vs Intrinsic %.1f | Edge Rs.%.0f",
            peQuote.lastPrice, intrinsicValue, opp.edgePoints);

        return opp;
    }

    private ArbitrageOpportunity buildSkewOpportunity(String underlying, int strike,
            OptionQuote ceQuote, OptionQuote peQuote, double ceIV, double peIV,
            double daysToExpiry, double spotPrice, double futuresPrice) {

        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.type = "SKEW_ANOMALY";
        opp.underlying = underlying;
        opp.strike = strike;
        opp.daysToExpiry = daysToExpiry;
        opp.spotPrice = spotPrice;
        opp.futuresPrice = futuresPrice;
        opp.cePrice = ceQuote.lastPrice;
        opp.pePrice = peQuote.lastPrice;
        opp.ceBid = ceQuote.bid;
        opp.ceAsk = ceQuote.ask;
        opp.peBid = peQuote.bid;
        opp.peAsk = peQuote.ask;

        double ivDiff = (peIV - ceIV) * 100;
        opp.edgePoints = ivDiff;
        opp.confidence = Math.min(80, 60 + (int)(ivDiff / 2));

        double expectedIVReversion = ivDiff * 0.20;
        double vegaPer1Pct = spotPrice * 0.004 * Math.sqrt(daysToExpiry / 365.0);
        int lotSize = getLotSize(underlying);
        double grossEdge = expectedIVReversion * vegaPer1Pct * lotSize;

        double sttEntry = peQuote.lastPrice * 0.001 * lotSize + futuresPrice * 0.0002 * lotSize;
        double sttExit = ceQuote.lastPrice * 0.001 * lotSize + futuresPrice * 0.0002 * lotSize;
        double totalSTT = sttEntry + sttExit;
        double brokerage = 20 * 6;
        double turnover = (ceQuote.lastPrice + peQuote.lastPrice + futuresPrice) * lotSize * 2;
        double exchange = turnover * 0.0000345;
        double sebi = turnover * 0.000001;
        double gst = (brokerage + exchange) * 0.18;
        double totalCosts = totalSTT + brokerage + exchange + sebi + gst;

        opp.edgeAfterCosts = Math.max(0, grossEdge - totalCosts);

        opp.action = "SELL_PUT_BUY_CALL";
        opp.legs = String.format(
            "SELL %d PE @ %.1f (IV %.1f%%) | BUY %d CE @ %.1f (IV %.1f%%) | SELL %s FUT @ %.0f",
            strike, peQuote.lastPrice, peIV * 100, strike, ceQuote.lastPrice, ceIV * 100,
            underlying, futuresPrice > 0 ? futuresPrice : spotPrice);
        opp.description = String.format(
            "Skew anomaly: Put IV %.1f%% >> Call IV %.1f%%. IV diff %.1f%%, expected 20%% reversion. Edge Rs.%.0f after costs",
            peIV * 100, ceIV * 100, ivDiff, opp.edgeAfterCosts);

        return opp;
    }

    private double getDouble(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private double getDepthPrice(Map<String, Object> quoteData, String side) {
        try {
            Map<String, Object> depth = (Map<String, Object>) quoteData.get("depth");
            if (depth == null) return 0;
            java.util.List<Map<String, Object>> levels = (java.util.List<Map<String, Object>>) depth.get(side);
            if (levels == null || levels.isEmpty()) return 0;
            Map<String, Object> best = levels.get(0);
            Object price = best.get("price");
            if (price instanceof Number) return ((Number) price).doubleValue();
        } catch (Exception e) {
            // depth parsing failed
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private double getNestedDouble(Map<String, Object> map, String... keys) {
        Object current = map;
        for (int i = 0; i < keys.length - 1; i++) {
            if (!(current instanceof Map)) return 0;
            current = ((Map<String, Object>) current).get(keys[i]);
            if (current == null) return 0;
        }
        if (!(current instanceof Map)) return 0;
        Object val = ((Map<String, Object>) current).get(keys[keys.length - 1]);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return 0;
    }

    private long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).longValue();
        return 0;
    }

    public static class OptionQuote {
        public String instrument;
        public double lastPrice;
        public double bid;
        public double ask;
        public long openInterest;
        public long volume;
        public double open, high, low, close;
    }
}
