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
    private final java.util.Map<String, String> resolvedSymbolCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(OptionChainService.class);

    private final ZerodhaTokenManager tokenManager;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${zerodha.api-key:$ZERODHA_API_KEY}")
    private String apiKey;

    private static final double RISK_FREE_RATE = 0.065;
    private static final double MIN_PARITY_DEVIATION = 0.5;
    private static final double MIN_EDGE_AFTER_COSTS = 0.0;

    private final ConcurrentHashMap<String, Long> cooldownMap = new ConcurrentHashMap<>();

    public OptionChainService(ZerodhaTokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public List<ArbitrageOpportunity> scanOptionChain(String underlying, double spotPrice, double futuresPrice) {
        return scanOptionChain(underlying, spotPrice, futuresPrice, false);
    }

    public List<ArbitrageOpportunity> scanOptionChain(String underlying, double spotPrice, double futuresPrice, boolean bypassCooldown) {
        List<ArbitrageOpportunity> opportunities = new ArrayList<>();

        try {
            int atmStrike = getATMStrike(underlying, spotPrice);
            List<Integer> strikes = generateStrikes(atmStrike, underlying);
            LocalDate expiryDate = getWeeklyExpiryDate(underlying);

            double daysToExpiry = Duration.between(LocalDate.now().atStartOfDay(), expiryDate.atStartOfDay()).toDays();
            double yearsToExpiry = Math.max(daysToExpiry, 0.5) / 365.0;

            List<String> instruments = new ArrayList<>();
            for (int strike : strikes) {
                instruments.addAll(buildNfoSymbolCandidates(underlying, expiryDate, strike, "CE"));
                instruments.addAll(buildNfoSymbolCandidates(underlying, expiryDate, strike, "PE"));
            }

            log.info("Scanning {} strikes for {} (ATM={}, spot={}, fut={})", strikes.size(), underlying, atmStrike, spotPrice, futuresPrice);

            Map<String, OptionQuote> quotes = fetchQuotes(instruments);

            log.info("Got {} quotes back for {}", quotes.size(), underlying);

            int validStrikes = 0;
            for (int strike : strikes) {
                List<String> ceCandidates = buildNfoSymbolCandidates(underlying, expiryDate, strike, "CE");
                List<String> peCandidates = buildNfoSymbolCandidates(underlying, expiryDate, strike, "PE");

                OptionQuote ceQuote = getFirstValidQuote(quotes, ceCandidates);
                OptionQuote peQuote = getFirstValidQuote(quotes, peCandidates);

                if (ceQuote == null || peQuote == null) continue;
                if (ceQuote.lastPrice <= 0 || peQuote.lastPrice <= 0) continue;

                validStrikes++;

                double ceExec = ceQuote.ask > 0 ? ceQuote.ask : ceQuote.lastPrice;
                double peExec = peQuote.bid > 0 ? peQuote.bid : peQuote.lastPrice;
                double parityDev = BlackScholesCalculator.parityDeviation(
                    ceExec, peExec, strike, RISK_FREE_RATE, yearsToExpiry, futuresPrice);

                if (Math.abs(parityDev) >= MIN_PARITY_DEVIATION) {
                    double grossEdge = Math.abs(parityDev) * getLotSize(underlying);
                    double edgeAfterCosts = calculateParityEdge(ceQuote.lastPrice, peQuote.lastPrice, futuresPrice, getLotSize(underlying), grossEdge);
                    opportunities.add(buildParityOpportunity(
                        underlying, strike, ceQuote, peQuote, parityDev,
                        edgeAfterCosts, daysToExpiry, spotPrice, futuresPrice));
                }
            }

            log.info("Scan completed for {}: {} valid strikes, {} opportunities found",
                underlying, validStrikes, opportunities.size());

        } catch (Exception e) {
            log.error("Error scanning option chain for {}: {}", underlying, e.getMessage(), e);
        }

        return opportunities;
    }

    private OptionQuote getFirstValidQuote(Map<String, OptionQuote> quotes, List<String> candidates) {
        for (String sym : candidates) {
            OptionQuote q = quotes.get(sym);
            if (q != null && q.lastPrice > 0) return q;
        }
        return null;
    }

    public Map<String, OptionQuote> fetchQuotes(List<String> instruments) {
        Map<String, OptionQuote> quotes = new ConcurrentHashMap<>();
        if (instruments == null || instruments.isEmpty()) return quotes;

        try {
            ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
            String token = auth != null ? auth.getAccessToken() : null;

            if (token == null || token.isBlank()) {
                log.error("No valid Zerodha access token for quotes");
                return quotes;
            }

            List<String> uniqueInstruments = new ArrayList<>(new LinkedHashSet<>(instruments));

            for (int i = 0; i < uniqueInstruments.size(); i += 100) {
                int end = Math.min(i + 100, uniqueInstruments.size());
                List<String> batch = uniqueInstruments.subList(i, end);

                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < batch.size(); j++) {
                    if (j > 0) sb.append("&i=");
                    String item = batch.get(j); sb.append(item.startsWith("NFO:") ? item : "NFO:" + item);
                }

                String url = "https://api.kite.trade/quote?i=" + sb.toString();

                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Kite-Version", "3");
                headers.set("Authorization", "token " + apiKey + ":" + token);

                HttpEntity<String> entity = new HttpEntity<>(headers);
                ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
                    if (data != null) {
                        for (Map.Entry<String, Object> entry : data.entrySet()) {
                            String rawKey = entry.getKey();
                            String cleanKey = rawKey.replace("NFO:", "");
                            Map<String, Object> qData = (Map<String, Object>) entry.getValue();

                            OptionQuote q = new OptionQuote();
                            q.symbol = cleanKey;
                            q.lastPrice = getDoubleValue(qData, "last_price");
                            q.volume = getIntValue(qData, "volume");
                            q.openInterest = getIntValue(qData, "oi");

                            Map<String, Object> depth = (Map<String, Object>) qData.get("depth");
                            if (depth != null) {
                                List<Map<String, Object>> buyList = (List<Map<String, Object>>) depth.get("buy");
                                List<Map<String, Object>> sellList = (List<Map<String, Object>>) depth.get("sell");
                                if (buyList != null && !buyList.isEmpty()) {
                                    q.bid = getDoubleValue(buyList.get(0), "price");
                                    q.bidQty = getIntValue(buyList.get(0), "quantity");
                                }
                                if (sellList != null && !sellList.isEmpty()) {
                                    q.ask = getDoubleValue(sellList.get(0), "price");
                                    q.askQty = getIntValue(sellList.get(0), "quantity");
                                }
                            }

                            quotes.put(cleanKey, q);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch quotes from Zerodha: {}", e.getMessage());
        }

        return quotes;
    }

    public static int getATMStrike(String underlying, double spotPrice) {
        int step = getStrikeStep(underlying);
        return (int) (Math.round(spotPrice / step) * step);
    }

    public static int getStrikeStep(String underlying) {
        return switch (underlying.toUpperCase()) {
            case "BANKNIFTY" -> 100;
            case "MIDCPNIFTY" -> 25;
            case "FINNIFTY" -> 50;
            default -> 50; // NIFTY
        };
    }

    /** Refreshed daily from Zerodha's live instrument dump by LotSizeService -- NSE revises
     *  lot sizes periodically (confirmed via a real Kite instruments fetch: NIFTY=65,
     *  BANKNIFTY=30, not the 25/15 that used to be hardcoded), so a static table here goes
     *  stale on its own schedule with no warning. This cache is the source of truth when
     *  populated; the switch below is only the fallback for before the first successful
     *  refresh or if a refresh fails -- kept as current as the values were last confirmed,
     *  but never a substitute for the live fetch actually working. */
    private static final Map<String, Integer> DYNAMIC_LOT_SIZES = new ConcurrentHashMap<>();

    public static void updateLotSizes(Map<String, Integer> fresh) {
        if (fresh != null && !fresh.isEmpty()) DYNAMIC_LOT_SIZES.putAll(fresh);
    }

    public static int getLotSize(String underlying) {
        String key = underlying.toUpperCase();
        Integer dynamic = DYNAMIC_LOT_SIZES.get(key);
        if (dynamic != null && dynamic > 0) return dynamic;
        return switch (key) {
            case "NIFTY" -> 25;
            case "BANKNIFTY" -> 15;
            case "MIDCPNIFTY" -> 50;
            case "FINNIFTY" -> 25;
            default -> 25;
        };
    }

    public List<Integer> generateStrikes(int atmStrike, String underlying) {
        int step = getStrikeStep(underlying);
        List<Integer> strikes = new ArrayList<>();
        for (int i = -5; i <= 5; i++) {
            strikes.add(atmStrike + i * step);
        }
        return strikes;
    }

    private DayOfWeek getExpiryDayForUnderlying(String underlying) {
        return switch (underlying.toUpperCase()) {
            case "BANKNIFTY" -> DayOfWeek.WEDNESDAY;
            case "FINNIFTY" -> DayOfWeek.TUESDAY;
            case "MIDCPNIFTY" -> DayOfWeek.MONDAY;
            default -> DayOfWeek.TUESDAY; // NIFTY — SEBI changed from Thursday to Tuesday
        };
    }


    public LocalDate getMonthlyExpiryDate(String underlying) {
        LocalDate today = LocalDate.now();
        DayOfWeek targetDay = getExpiryDayForUnderlying(underlying);
        LocalDate lastDayOfMonth = today.withDayOfMonth(today.lengthOfMonth());
        LocalDate expiryDay = lastDayOfMonth;
        while (expiryDay.getDayOfWeek() != targetDay) {
            expiryDay = expiryDay.minusDays(1);
        }
        if (expiryDay.isBefore(today) || (expiryDay.equals(today) && LocalTime.now(ZoneId.of("Asia/Kolkata")).isAfter(LocalTime.of(15, 30)))) {
            lastDayOfMonth = today.plusMonths(1).withDayOfMonth(today.plusMonths(1).lengthOfMonth());
            expiryDay = lastDayOfMonth;
            while (expiryDay.getDayOfWeek() != targetDay) {
                expiryDay = expiryDay.minusDays(1);
            }
        }
        return expiryDay;
    }
    public LocalDate getWeeklyExpiryDate(String underlying) {
        LocalDate today = LocalDate.now();
        LocalDate nextExpiry = today;
        DayOfWeek targetDay = getExpiryDayForUnderlying(underlying);

        while (nextExpiry.getDayOfWeek() != targetDay) {
            nextExpiry = nextExpiry.plusDays(1);
        }

        if (nextExpiry.equals(today)) {
            LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
            if (nowIST.isAfter(LocalTime.of(15, 30))) {
                nextExpiry = nextExpiry.plusWeeks(1);
            }
        }

        return nextExpiry;
    }

    public LocalDate getMonthlyExpiry() {
        LocalDate today = LocalDate.now();
        LocalDate lastDayOfMonth = today.withDayOfMonth(today.lengthOfMonth());
        LocalDate expiryDay = lastDayOfMonth;
        while (expiryDay.getDayOfWeek() != DayOfWeek.TUESDAY) {
            expiryDay = expiryDay.minusDays(1);
        }
        if (expiryDay.equals(today)) {
            LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
            if (nowIST.isAfter(LocalTime.of(15, 30))) {
                expiryDay = expiryDay.plusMonths(1);
                lastDayOfMonth = expiryDay.withDayOfMonth(expiryDay.lengthOfMonth());
                expiryDay = lastDayOfMonth;
                while (expiryDay.getDayOfWeek() != DayOfWeek.TUESDAY) {
                    expiryDay = expiryDay.minusDays(1);
                }
            }
        }
        return expiryDay;
    }

    public List<String> buildNfoSymbolCandidates(String underlying, LocalDate expiryDate, int strike, String type) {
        String cleanUnderlying = underlying.replace(" ", "");
        int yy = expiryDate.getYear() % 100;
        String mon = expiryDate.getMonth().name().substring(0, 3);
        int month = expiryDate.getMonthValue();
        int day = expiryDate.getDayOfMonth();

        String mCode = (month == 10) ? "O" : (month == 11) ? "N" : (month == 12) ? "D" : String.valueOf(month);

        List<String> list = new ArrayList<>();
        // 1. Monthly format: NIFTY26JUL23950CE
        list.add(String.format("%s%02d%s%d%s", cleanUnderlying, yy, mon, strike, type));
        // 2. Weekly format: NSE standard (1-9, O, N, D)
        list.add(String.format("%s%02d%s%02d%d%s", cleanUnderlying, yy, mCode, day, strike, type));
        // 3. Fallback math format
        list.add(String.format("%s%02d%d%02d%d%s", cleanUnderlying, yy, month, day, strike, type));
        return list;
    }

    public String buildNfoSymbol(String underlying, LocalDate expiryDate, int strike, String type) {
        String cacheKey = underlying + "|" + expiryDate + "|" + strike + "|" + type;
        if (resolvedSymbolCache.containsKey(cacheKey)) return resolvedSymbolCache.get(cacheKey);

        List<String> candidates = buildNfoSymbolCandidates(underlying, expiryDate, strike, type);
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        boolean isMonthly = (expiryDate.plusDays(7).getMonthValue() != expiryDate.getMonthValue());
        String mathGuess = isMonthly ? candidates.get(0) : candidates.get(1);

        try {
            java.util.Map<String, OptionQuote> quotes = fetchQuotes(candidates);
            for (String sym : candidates) {
                OptionQuote q = quotes.get(sym);
                if (q != null && q.lastPrice > 0) { resolvedSymbolCache.put(cacheKey, q.symbol); return q.symbol; }
            }
        } catch (Exception ignored) {}
        return mathGuess;
    }

    public String buildNfoFutSymbol(String underlying, LocalDate expiryDate) {
        String clean = underlying.replace(" ", "");
        int yy = expiryDate.getYear() % 100;
        String mon = expiryDate.getMonth().name().substring(0, 3);
        return String.format("%s%02d%sFUT", clean, yy, mon);
    }

    private double calculateParityEdge(double cePrice, double pePrice, double futPrice, int lotSize, double grossEdge) {
        return ArbitrageCosts.netEdge(cePrice, pePrice, futPrice, lotSize, grossEdge);
    }

    private ArbitrageOpportunity buildParityOpportunity(String underlying, int strike,
            OptionQuote ceQuote, OptionQuote peQuote, double parityDev,
            double edgeAfterCosts, double daysToExpiry, double spotPrice, double futuresPrice) {

        ArbitrageOpportunity opp = new ArbitrageOpportunity();
        opp.underlying = underlying;
        opp.strike = strike;
        opp.type = "PARITY_BREAK";
        opp.detectedAt = LocalDateTime.now();
        opp.spotPrice = spotPrice;
        opp.futuresPrice = futuresPrice;
        opp.cePrice = ceQuote.lastPrice;
        opp.pePrice = peQuote.lastPrice;
        opp.ceBid = ceQuote.bid;
        opp.ceAsk = ceQuote.ask;
        opp.peBid = peQuote.bid;
        opp.peAsk = peQuote.ask;
        opp.edgePoints = Math.round(Math.abs(parityDev) * 10.0) / 10.0;
        opp.edgeAfterCosts = Math.round(edgeAfterCosts * 10.0) / 10.0;
        opp.confidence = Math.min(99.0, 70.0 + Math.abs(parityDev) * 1.5);
        opp.daysToExpiry = daysToExpiry;

        if (parityDev > 0) {
            opp.action = "BUY FUT + SELL CE + BUY PE";
            opp.legs = String.format("SELL %d CE @ %.1f | BUY %d PE @ %.1f | BUY %s FUT @ %.1f",
                strike, ceQuote.bid, strike, peQuote.ask, underlying, futuresPrice);
        } else {
            opp.action = "BUY CE + SELL PE + SELL FUT";
            opp.legs = String.format("BUY %d CE @ %.1f | SELL %d PE @ %.1f | SELL %s FUT @ %.1f",
                strike, ceQuote.ask, strike, peQuote.bid, underlying, futuresPrice);
        }

        return opp;
    }

    private double getDoubleValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private int getIntValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return 0;
    }

    public static class OptionQuote {
        public String symbol;
        public double lastPrice;
        public double bid;
        public double ask;
        public int bidQty;
        public int askQty;
        public int volume;
        public int openInterest;
    }
}


