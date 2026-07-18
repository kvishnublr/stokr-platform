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
    private static final double MIN_PARITY_DEVIATION = 8.0;
    private static final double MIN_EDGE_AFTER_COSTS = 300.0;
    private static final double MAX_SPREAD_PCT = 2.0;
    private static final double MAX_SPREAD_POINTS = 8.0;
    private static final int COOLDOWN_SECONDS = 60;
    private static final int MIN_VOLUME = 100;
    private static final int MIN_OI = 100;

    private static final Map<String, int[]> DTE_RANGES = Map.of(
        "NIFTY",     new int[]{3, 7},
        "BANKNIFTY", new int[]{3, 21},
        "MIDCPNIFTY", new int[]{3, 21},
        "FINNIFTY",  new int[]{3, 21}
    );

    private final ConcurrentHashMap<String, Long> cooldownMap = new ConcurrentHashMap<>();

    public OptionChainService(ZerodhaTokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public List<ArbitrageOpportunity> scanOptionChain(String underlying, double spotPrice, double futuresPrice) {
        return scanOptionChain(underlying, spotPrice, futuresPrice, false);
    }

    public List<ArbitrageOpportunity> scanOptionChain(String underlying, double spotPrice, double futuresPrice, boolean bypassCooldown) {
        return scanOptionChain(underlying, spotPrice, futuresPrice, bypassCooldown, false);
    }

    public List<ArbitrageOpportunity> scanOptionChain(String underlying, double spotPrice, double futuresPrice, boolean bypassCooldown, boolean debugMode) {
        List<ArbitrageOpportunity> opportunities = new ArrayList<>();

        try {
            int atmStrike = getATMStrike(underlying, spotPrice);
            List<Integer> strikes = generateStrikes(atmStrike, underlying);

            LocalDate expiryDate = getWeeklyExpiryDate(underlying);
            double daysToExpiry = Duration.between(LocalDate.now().atStartOfDay(), expiryDate.atStartOfDay()).toDays();
            double yearsToExpiry = daysToExpiry / 365.0;

            if (daysToExpiry < 0) {
                log.warn("No future expiry found for {}, skipping scan", underlying);
                return opportunities;
            }

            int[] dteRange = DTE_RANGES.getOrDefault(underlying, new int[]{3, 21});
            if ((int) daysToExpiry < dteRange[0] || (int) daysToExpiry > dteRange[1]) {
                log.info("DTE {} outside [{}, {}] for {}, skipping scan", (int) daysToExpiry, dteRange[0], dteRange[1], underlying);
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
            int debugStrikes = 0;
            for (int strike : strikes) {
                String ceKey = buildNfoSymbol(underlying, expiryDate, strike, "CE");
                String peKey = buildNfoSymbol(underlying, expiryDate, strike, "PE");

                OptionQuote ceQuote = quotes.get(ceKey);
                OptionQuote peQuote = quotes.get(peKey);

                if (ceQuote == null || peQuote == null) continue;
                if (ceQuote.lastPrice <= 0 && peQuote.lastPrice <= 0) continue;

                if (debugMode) {
                    ArbitrageOpportunity debugOpp = new ArbitrageOpportunity();
                    debugOpp.type = "DEBUG";
                    debugOpp.action = "INFO";
                    debugOpp.underlying = underlying;
                    debugOpp.strike = strike;
                    debugOpp.spotPrice = spotPrice;
                    debugOpp.futuresPrice = futuresPrice;
                    debugOpp.cePrice = ceQuote.lastPrice;
                    debugOpp.pePrice = peQuote.lastPrice;
                    debugOpp.ceBid = ceQuote.bid;
                    debugOpp.ceAsk = ceQuote.ask;
                    debugOpp.peBid = peQuote.bid;
                    debugOpp.peAsk = peQuote.ask;
                    debugOpp.daysToExpiry = daysToExpiry;
                    debugOpp.description = String.format("CE: last=%.1f bid=%.1f ask=%.1f vol=%d oi=%d | PE: last=%.1f bid=%.1f ask=%.1f vol=%d oi=%d",
                        ceQuote.lastPrice, ceQuote.bid, ceQuote.ask, ceQuote.volume, ceQuote.openInterest,
                        peQuote.lastPrice, peQuote.bid, peQuote.ask, peQuote.volume, peQuote.openInterest);
                    debugOpp.legs = isSpreadTooWide(ceQuote) || isSpreadTooWide(peQuote) ? "SPREAD_WIDE" : "OK";
                    debugOpp.confidence = (ceQuote.volume >= MIN_VOLUME && peQuote.volume >= MIN_VOLUME) ? 80.0 : 30.0;
                    debugOpp.edgePoints = 0;
                    debugOpp.edgeAfterCosts = 0;
                    opportunities.add(debugOpp);
                    debugStrikes++;
                }
                if (isSpreadTooWide(ceQuote) || isSpreadTooWide(peQuote)) continue;
                if (ceQuote.volume < MIN_VOLUME || peQuote.volume < MIN_VOLUME) continue;
                if (ceQuote.openInterest < MIN_OI || peQuote.openInterest < MIN_OI) continue;

                validStrikes++;

                // 1. Put-Call Parity
                // Use bid/ask instead of lastPrice to reflect actual execution prices
                // CONVERSION: BUY CE (pay ask) + SELL PE (receive bid)
                double ceExec = ceQuote.ask > 0 ? ceQuote.ask : ceQuote.lastPrice;
                double peExec = peQuote.bid > 0 ? peQuote.bid : peQuote.lastPrice;
                double parityDev = BlackScholesCalculator.parityDeviation(
                    ceExec, peExec, strike, RISK_FREE_RATE, yearsToExpiry, futuresPrice);

                if (Math.abs(parityDev) >= MIN_PARITY_DEVIATION) {
                    String cooldownKey = underlying + "_" + strike + "_PARITY";
                    if (bypassCooldown || !isOnCooldown(cooldownKey)) {
                        double edgeAfterCosts = calculateParityEdge(parityDev, underlying);
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
                    if (bypassCooldown || !isOnCooldown(cooldownKey)) {
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
                            if (bypassCooldown || !isOnCooldown(cooldownKey)) {
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
                    if (bypassCooldown || !isOnCooldown(cooldownKey)) {
                        opportunities.add(buildSkewOpportunity(
                            underlying, strike, ceQuote, peQuote, ceIV, peIV, daysToExpiry, spotPrice));
                        cooldownMap.put(cooldownKey, System.currentTimeMillis());
                    }
                }
            }

            log.info("Analyzed {} valid strikes for {}{} found {} opportunities", validStrikes, underlying, debugMode ? " (debug " + debugStrikes + " strikes)" : "", opportunities.size());

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

    private String getAuthToken() {
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
        switch (underlying) {
            case "BANKNIFTY": return (int) Math.round(spotPrice / 100.0) * 100;
            case "MIDCPNIFTY": return (int) Math.round(spotPrice / 50.0) * 50;
            case "FINNIFTY": return (int) Math.round(spotPrice / 50.0) * 50;
            default: return (int) Math.round(spotPrice / 50.0) * 50;  // NIFTY
        }
    }

    private List<Integer> generateStrikes(int atmStrike, String underlying) {
        List<Integer> strikes = new ArrayList<>();
        int step;
        int range;
        switch (underlying) {
            case "BANKNIFTY": step = 100; range = 3; break;
            case "MIDCPNIFTY": step = 50; range = 3; break;
            case "FINNIFTY": step = 50; range = 3; break;
            default: step = 50; range = 3; break;  // NIFTY
        }
        for (int i = -range; i <= range; i++) {
            strikes.add(atmStrike + i * step);
        }
        return strikes;
    }

    private LocalDate getWeeklyExpiryDate(String underlying) {
        LocalDate today = LocalDate.now();
        LocalDate nextExpiry = today;

        // NIFTY has weekly (Tuesday) + monthly, others only monthly
        if ("NIFTY".equals(underlying)) {
            // Weekly expiry = next Tuesday
            while (nextExpiry.getDayOfWeek() != DayOfWeek.TUESDAY) {
                nextExpiry = nextExpiry.plusDays(1);
            }
            if (nextExpiry.equals(today)) {
                java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
                if (nowIST.isAfter(java.time.LocalTime.of(15, 0))) {
                    nextExpiry = nextExpiry.plusWeeks(1);
                }
            }
        } else {
            // BANKNIFTY/MIDCPNIFTY/FINNIFTY = monthly expiry only (last Tuesday of month)
            nextExpiry = getMonthlyExpiryDate(underlying);
        }

        return nextExpiry;
    }

    private LocalDate getMonthlyExpiryDate(String underlying) {
        LocalDate today = LocalDate.now();
        // Monthly expiry = last TUESDAY of the month
        LocalDate lastTuesday = today.withDayOfMonth(today.lengthOfMonth());
        while (lastTuesday.getDayOfWeek() != DayOfWeek.TUESDAY) {
            lastTuesday = lastTuesday.minusDays(1);
        }
        if (lastTuesday.isBefore(today)) {
            lastTuesday = lastTuesday.plusMonths(1);
            lastTuesday = lastTuesday.withDayOfMonth(lastTuesday.lengthOfMonth());
            while (lastTuesday.getDayOfWeek() != DayOfWeek.TUESDAY) {
                lastTuesday = lastTuesday.minusDays(1);
            }
        }
        return lastTuesday;
    }

    private String buildNfoSymbol(String underlying, LocalDate expiryDate, int strike, String type) {
        String cleanUnderlying = underlying.replace(" ", "");
        int yy = expiryDate.getYear() % 100;

        // Monthly expiry: use month abbreviation (e.g., JUL) — no day
        // Weekly expiry (NIFTY only): use month number + day (e.g., 721)
        LocalDate today = LocalDate.now();
        LocalDate monthly = getMonthlyExpiryDate(underlying);
        boolean isMonthly = expiryDate.equals(monthly) || expiryDate.getDayOfWeek() == DayOfWeek.TUESDAY && !expiryDate.equals(today);

        // Check if this underlying only has monthly (no weekly)
        boolean hasWeekly = "NIFTY".equals(underlying);

        if (!hasWeekly || expiryDate.equals(monthly)) {
            // Monthly format: UNDERLYING + YY + MON + Strike + Type
            String mon = expiryDate.getMonth().name().substring(0, 3);
            return String.format("%s%02d%s%d%s", cleanUnderlying, yy, mon, strike, type);
        } else {
            // Weekly format: NIFTY + YY + M + DD + Strike + Type
            int month = expiryDate.getMonthValue();
            int day = expiryDate.getDayOfMonth();
            return String.format("%s%02d%d%02d%d%s", cleanUnderlying, yy, month, day, strike, type);
        }
    }

    private boolean isSpreadTooWide(OptionQuote quote) {
        if (quote.bid <= 0 || quote.ask <= 0) return true;
        double spreadPoints = quote.ask - quote.bid;
        double spreadPct = spreadPoints / quote.ask * 100;
        return spreadPct > MAX_SPREAD_PCT || spreadPoints > MAX_SPREAD_POINTS;
    }

    private boolean isOnCooldown(String key) {
        Long lastAlert = cooldownMap.get(key);
        if (lastAlert == null) return false;
        return (System.currentTimeMillis() - lastAlert) < COOLDOWN_SECONDS * 1000L;
    }

    private double calculateParityEdge(double parityDev, String underlying) {
        double rawEdge = Math.abs(parityDev);
        int lotSize = getLotSize(underlying);
        double grossEdge = rawEdge * lotSize;

        // STT: 0.1% on sell options + 0.02% on sell futures
        // For reversal: sell CE + sell PE + buy FUT (entry), then buy CE + buy PE + sell FUT (exit)
        // STT = (CE+PE)*0.001 + FUT*0.0002
        double stt = grossEdge * 0.001;  // options STT
        double brokerage = 120.0;  // ₹20 × 6 orders
        double exchange = grossEdge * 0.0000345;  // 0.00345%
        double sebi = grossEdge * 0.000001;  // 0.0001%
        double gst = (brokerage + sebi) * 0.18;  // 18% GST
        double ipft = grossEdge * 0.0000001;  // 0.00001%

        double totalCosts = stt + brokerage + exchange + sebi + gst + ipft;
        return grossEdge - totalCosts;
    }

    public static int getLotSize(String underlying) {
        switch (underlying) {
            case "BANKNIFTY": return 30;
            case "MIDCPNIFTY": return 120;
            case "FINNIFTY": return 60;
            default: return 65;  // NIFTY
        }
    }

    /**
     * Calculate detailed cost breakdown for display in UI
     */
    public Map<String, Double> calculateCostBreakdown(double parityDev, String underlying) {
        double rawEdge = Math.abs(parityDev);
        int lotSize = getLotSize(underlying);
        double grossEdge = rawEdge * lotSize;

        double stt = grossEdge * 0.001;
        double brokerage = 120.0;
        double exchange = grossEdge * 0.0000345;
        double sebi = grossEdge * 0.000001;
        double gst = (brokerage + sebi) * 0.18;
        double ipft = grossEdge * 0.0000001;

        Map<String, Double> costs = new LinkedHashMap<>();
        costs.put("grossEdge", grossEdge);
        costs.put("stt", stt);
        costs.put("brokerage", brokerage);
        costs.put("exchange", exchange);
        costs.put("sebi", sebi);
        costs.put("gst", gst);
        costs.put("ipft", ipft);
        costs.put("totalCosts", stt + brokerage + exchange + sebi + gst + ipft);
        costs.put("netEdge", grossEdge - (stt + brokerage + exchange + sebi + gst + ipft));
        costs.put("lotSize", (double) lotSize);
        return costs;
    }

    public LocalDate getWeeklyExpiry() {
        return getWeeklyExpiryDate("NIFTY");
    }

    public LocalDate getMonthlyExpiry() {
        LocalDate today = LocalDate.now();
        LocalDate lastTuesday = today.withDayOfMonth(today.lengthOfMonth());
        while (lastTuesday.getDayOfWeek() != DayOfWeek.TUESDAY) {
            lastTuesday = lastTuesday.minusDays(1);
        }
        if (lastTuesday.isBefore(today)) {
            lastTuesday = lastTuesday.plusMonths(1);
            lastTuesday = lastTuesday.withDayOfMonth(lastTuesday.lengthOfMonth());
            while (lastTuesday.getDayOfWeek() != DayOfWeek.TUESDAY) {
                lastTuesday = lastTuesday.minusDays(1);
            }
        }
        return lastTuesday;
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

        opp.costBreakdown = calculateCostBreakdown(parityDev, underlying);

        opp.description = String.format(
            "Parity break: Synthetic %.1f vs Futures %.0f | Deviation %.1f pts | Edge ₹%.0f after costs",
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
            "Deep ITM PE below intrinsic: Market %.1f vs Intrinsic %.1f | Edge ₹%.0f",
            peQuote.lastPrice, intrinsicValue, opp.edgePoints);

        return opp;
    }

    private ArbitrageOpportunity buildSkewOpportunity(String underlying, int strike,
            OptionQuote ceQuote, OptionQuote peQuote, double ceIV, double peIV,
            double daysToExpiry, double spotPrice) {

        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.type = "SKEW_ANOMALY";
        opp.underlying = underlying;
        opp.strike = strike;
        opp.edgePoints = (peIV - ceIV) * 100;
        opp.daysToExpiry = daysToExpiry;
        opp.spotPrice = spotPrice;
        opp.cePrice = ceQuote.lastPrice;
        opp.pePrice = peQuote.lastPrice;
        opp.confidence = 70;
        opp.action = "SELL_PUT_BUY_CALL";
        opp.legs = String.format(
            "SELL %d PE @ %.1f (IV %.1f%%) | BUY %d CE @ %.1f (IV %.1f%%)",
            strike, peQuote.lastPrice, peIV * 100, strike, ceQuote.lastPrice, ceIV * 100);
        opp.description = String.format(
            "Skew anomaly: Put IV %.1f%% >> Call IV %.1f%%. Sell expensive puts, buy cheap calls",
            peIV * 100, ceIV * 100);

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
            Object depthObj = quoteData.get("depth");
            if (!(depthObj instanceof Map)) return 0;
            Object sideObj = ((Map<String, Object>) depthObj).get(side);
            if (!(sideObj instanceof List)) return 0;
            List<Object> levels = (List<Object>) sideObj;
            if (levels.isEmpty()) return 0;
            Object first = levels.get(0);
            if (!(first instanceof Map)) return 0;
            Object price = ((Map<String, Object>) first).get("price");
            if (price instanceof Number) return ((Number) price).doubleValue();
        } catch (Exception e) {
            return 0;
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
