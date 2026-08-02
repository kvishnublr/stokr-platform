package com.stokr.arbitrage;

import com.stokr.external.ZerodhaTokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OptionChainService {

    private static final Logger log = LoggerFactory.getLogger(OptionChainService.class);

    private final ZerodhaTokenManager tokenManager;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${zerodha.api-key:$ZERODHA_API_KEY}")
    private String apiKey;

    private static final double RISK_FREE_RATE = 0.065;
    /** Minimum executable parity edge in index points before cost. */
    private static final double MIN_PARITY_DEVIATION = 2.0;
    /** Minimum net edge after costs (₹) to publish an opportunity. */
    private static final double MIN_EDGE_AFTER_COSTS = 150.0;
    /** Skip strikes where option spread is wider than this (pts). */
    private static final double MAX_OPTION_SPREAD = 25.0;

    public OptionChainService(ZerodhaTokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public List<ArbitrageOpportunity> scanOptionChain(String underlying, double spotPrice, double futuresPrice) {
        return scanOptionChain(underlying, spotPrice, futuresPrice, false);
    }

    public List<ArbitrageOpportunity> scanOptionChain(String underlying, double spotPrice, double futuresPrice, boolean bypassCooldown) {
        // Default: weekly options (IV / misc scanners). Bid-parity must use monthly to match futures.
        return scanOptionChain(underlying, spotPrice, futuresPrice, bypassCooldown, false, null);
    }

    /**
     * Bid-parity / conversion-reversal scan against monthly index futures.
     * Uses monthly option expiry (optionally pinned to the futures contract month).
     */
    public List<ArbitrageOpportunity> scanBidParityChain(String underlying, double spotPrice, double futuresPrice,
                                                         LocalDate futuresExpiryHint) {
        return scanOptionChain(underlying, spotPrice, futuresPrice, false, true, futuresExpiryHint);
    }

    public List<ArbitrageOpportunity> scanOptionChain(String underlying, double spotPrice, double futuresPrice,
                                                      boolean bypassCooldown, boolean monthlyExpiry,
                                                      LocalDate futuresExpiryHint) {
        List<ArbitrageOpportunity> opportunities = new ArrayList<>();

        try {
            double refPrice = futuresPrice > 0 ? futuresPrice : spotPrice;
            if (refPrice <= 0) {
                log.warn("No valid spot/futures for {}, skipping scan", underlying);
                return opportunities;
            }

            int atmStrike = getATMStrike(underlying, refPrice);
            List<Integer> strikes = generateStrikes(atmStrike, underlying);
            LocalDate expiryDate = monthlyExpiry
                    ? (futuresExpiryHint != null ? futuresExpiryHint : getMonthlyExpiry(underlying))
                    : getWeeklyExpiryDate(underlying);

            double daysToExpiry = Duration.between(LocalDate.now().atStartOfDay(), expiryDate.atStartOfDay()).toDays();
            double yearsToExpiry = Math.max(daysToExpiry, 0.5) / 365.0;

            List<String> instruments = new ArrayList<>();
            for (int strike : strikes) {
                instruments.addAll(buildNfoSymbolCandidates(underlying, expiryDate, strike, "CE"));
                instruments.addAll(buildNfoSymbolCandidates(underlying, expiryDate, strike, "PE"));
            }

            log.info("Scanning {} strikes for {} (ATM={}, spot={}, fut={}, expiry={})",
                    strikes.size(), underlying, atmStrike, spotPrice, futuresPrice, expiryDate);

            Map<String, OptionQuote> quotes = fetchQuotes(instruments);
            log.info("Got {} quotes back for {}", quotes.size(), underlying);

            int validStrikes = 0;
            for (int strike : strikes) {
                OptionQuote ceQuote = getFirstValidQuote(quotes, buildNfoSymbolCandidates(underlying, expiryDate, strike, "CE"));
                OptionQuote peQuote = getFirstValidQuote(quotes, buildNfoSymbolCandidates(underlying, expiryDate, strike, "PE"));
                if (ceQuote == null || peQuote == null) continue;
                if (ceQuote.lastPrice <= 0 || peQuote.lastPrice <= 0) continue;
                if (ceQuote.bid <= 0 || ceQuote.ask <= 0 || peQuote.bid <= 0 || peQuote.ask <= 0) continue;
                if ((ceQuote.ask - ceQuote.bid) > MAX_OPTION_SPREAD) continue;
                if ((peQuote.ask - peQuote.bid) > MAX_OPTION_SPREAD) continue;

                validStrikes++;
                double dfK = strike * Math.exp(-RISK_FREE_RATE * yearsToExpiry);

                // CONVERSION (buy synthetic / sell futures): BUY CE@ask, SELL PE@bid, SELL FUT
                double synthBuy = ceQuote.ask - peQuote.bid + dfK;
                double conversionPts = futuresPrice - synthBuy;

                // REVERSAL (sell synthetic / buy futures): SELL CE@bid, BUY PE@ask, BUY FUT
                double synthSell = ceQuote.bid - peQuote.ask + dfK;
                double reversalPts = synthSell - futuresPrice;

                if (conversionPts >= MIN_PARITY_DEVIATION) {
                    double edge = calculateParityEdge(conversionPts, underlying);
                    if (edge >= MIN_EDGE_AFTER_COSTS) {
                        opportunities.add(buildParityOpportunity(
                                underlying, strike, ceQuote, peQuote, conversionPts, edge,
                                daysToExpiry, spotPrice, futuresPrice, true, expiryDate));
                    }
                }
                if (reversalPts >= MIN_PARITY_DEVIATION) {
                    double edge = calculateParityEdge(reversalPts, underlying);
                    if (edge >= MIN_EDGE_AFTER_COSTS) {
                        opportunities.add(buildParityOpportunity(
                                underlying, strike, ceQuote, peQuote, reversalPts, edge,
                                daysToExpiry, spotPrice, futuresPrice, false, expiryDate));
                    }
                }
            }

            opportunities.sort((a, b) -> Double.compare(b.edgeAfterCosts, a.edgeAfterCosts));
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

    @SuppressWarnings("unchecked")
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
                    sb.append("NFO:").append(batch.get(j));
                }

                String url = "https://api.kite.trade/quote?i=" + sb;
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Kite-Version", "3");
                headers.set("Authorization", "token " + apiKey + ":" + token);

                ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
                    if (data != null) {
                        for (Map.Entry<String, Object> entry : data.entrySet()) {
                            String cleanKey = entry.getKey().replace("NFO:", "");
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

    public int getATMStrike(String underlying, double spotPrice) {
        int step = getStrikeStep(underlying);
        return (int) (Math.round(spotPrice / step) * step);
    }

    public static int getStrikeStep(String underlying) {
        return switch (underlying.toUpperCase()) {
            case "BANKNIFTY" -> 100;
            case "MIDCPNIFTY" -> 25;
            case "FINNIFTY" -> 50;
            default -> 50;
        };
    }

    public static int getLotSize(String underlying) {
        return switch (underlying.toUpperCase()) {
            case "BANKNIFTY" -> 15;
            case "MIDCPNIFTY" -> 50;
            case "FINNIFTY" -> 25;
            default -> 25; // NIFTY
        };
    }

    public List<Integer> generateStrikes(int atmStrike, String underlying) {
        int step = getStrikeStep(underlying);
        List<Integer> strikes = new ArrayList<>();
        // Focus near ATM (±5) to avoid illiquid deep OTM false edges
        for (int i = -5; i <= 5; i++) {
            strikes.add(atmStrike + i * step);
        }
        return strikes;
    }

    private DayOfWeek getExpiryDayForUnderlying(String underlying) {
        // NSE weekly expiries (as of late 2024+): NIFTY Tue, BN Wed, FN Tue, MN Mon
        return switch (underlying.toUpperCase()) {
            case "BANKNIFTY" -> DayOfWeek.WEDNESDAY;
            case "FINNIFTY" -> DayOfWeek.TUESDAY;
            case "MIDCPNIFTY" -> DayOfWeek.MONDAY;
            default -> DayOfWeek.TUESDAY; // NIFTY weekly
        };
    }

    public LocalDate getWeeklyExpiryDate(String underlying) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
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

    /** NIFTY monthly (legacy default Tuesday). Prefer {@link #getMonthlyExpiry(String)}. */
    public LocalDate getMonthlyExpiry() {
        return getMonthlyExpiry("NIFTY");
    }

    /** Last monthly expiry on/after today for the underlying (IST). */
    public LocalDate getMonthlyExpiry(String underlying) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate expiry = lastExpiryOfMonth(underlying, today.getYear(), today.getMonthValue());
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        boolean expired = expiry.isBefore(today) || (expiry.equals(today) && nowIST.isAfter(LocalTime.of(15, 30)));
        if (expired) {
            LocalDate next = today.plusMonths(1);
            expiry = lastExpiryOfMonth(underlying, next.getYear(), next.getMonthValue());
        }
        return expiry;
    }

    /** Monthly expiry date for a specific contract year/month. */
    public LocalDate getMonthlyExpiryFor(String underlying, int year, int month) {
        return lastExpiryOfMonth(underlying, year, month);
    }

    private LocalDate lastExpiryOfMonth(String underlying, int year, int month) {
        DayOfWeek target = getExpiryDayForUnderlying(underlying);
        LocalDate d = LocalDate.of(year, month, 1).withDayOfMonth(LocalDate.of(year, month, 1).lengthOfMonth());
        while (d.getDayOfWeek() != target) {
            d = d.minusDays(1);
        }
        return d;
    }

    private List<String> buildNfoSymbolCandidates(String underlying, LocalDate expiryDate, int strike, String type) {
        String cleanUnderlying = underlying.replace(" ", "");
        int yy = expiryDate.getYear() % 100;
        String mon = expiryDate.getMonth().name().substring(0, 3);
        int month = expiryDate.getMonthValue();
        int day = expiryDate.getDayOfMonth();

        List<String> list = new ArrayList<>();
        LocalDate monthly = getMonthlyExpiryFor(underlying, expiryDate.getYear(), expiryDate.getMonthValue());
        if (expiryDate.equals(monthly)) {
            list.add(String.format("%s%02d%s%d%s", cleanUnderlying, yy, mon, strike, type));
        }
        list.add(String.format("%s%02d%d%02d%d%s", cleanUnderlying, yy, month, day, strike, type));
        // Always also try monthly format as fallback
        if (!expiryDate.equals(monthly)) {
            list.add(String.format("%s%02d%s%d%s", cleanUnderlying, yy, mon, strike, type));
        }
        return list;
    }

    /** Public wrapper for box/calendar scanners that need candidate NFO symbols. */
    public List<String> buildNfoSymbolCandidatesPublic(String underlying, LocalDate expiryDate, int strike, String type) {
        return buildNfoSymbolCandidates(underlying, expiryDate, strike, type);
    }

    public String buildNfoSymbol(String underlying, LocalDate expiryDate, int strike, String type) {
        List<String> candidates = buildNfoSymbolCandidates(underlying, expiryDate, strike, type);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private double calculateParityEdge(double parityDev, String underlying) {
        double pts = Math.abs(parityDev);
        int lotSize = getLotSize(underlying);
        double grossEdge = pts * lotSize;
        double stt = grossEdge * 0.001;
        double brokerage = 120.0; // ~3 legs * ₹40
        double exchange = grossEdge * 0.000345;
        double sebi = grossEdge * 0.000001;
        double gst = (brokerage + exchange) * 0.18;
        double totalCosts = stt + brokerage + exchange + sebi + gst;
        return Math.max(0, grossEdge - totalCosts);
    }

    private ArbitrageOpportunity buildParityOpportunity(String underlying, int strike,
            OptionQuote ceQuote, OptionQuote peQuote, double parityDev,
            double edgeAfterCosts, double daysToExpiry, double spotPrice, double futuresPrice,
            boolean conversion, LocalDate expiryDate) {

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
        opp.confidence = Math.min(99.0, 55.0 + Math.abs(parityDev) * 2.0);
        opp.daysToExpiry = daysToExpiry;
        opp.expiryDate = expiryDate;

        if (conversion) {
            // Buy synthetic, sell futures
            opp.action = "CONVERSION";
            opp.legs = String.format("BUY %d CE @ %.1f | SELL %d PE @ %.1f | SELL %s FUT @ %.1f",
                    strike, ceQuote.ask, strike, peQuote.bid, underlying, futuresPrice);
            opp.description = "Futures rich vs synthetic — BUY CE / SELL PE / SELL FUT";
        } else {
            // Sell synthetic, buy futures
            opp.action = "REVERSAL";
            opp.legs = String.format("SELL %d CE @ %.1f | BUY %d PE @ %.1f | BUY %s FUT @ %.1f",
                    strike, ceQuote.bid, strike, peQuote.ask, underlying, futuresPrice);
            opp.description = "Synthetic rich vs futures — SELL CE / BUY PE / BUY FUT";
        }

        Map<String, Double> costs = new LinkedHashMap<>();
        costs.put("grossPts", opp.edgePoints);
        costs.put("netInr", opp.edgeAfterCosts);
        costs.put("lotSize", (double) getLotSize(underlying));
        opp.costBreakdown = costs;
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
