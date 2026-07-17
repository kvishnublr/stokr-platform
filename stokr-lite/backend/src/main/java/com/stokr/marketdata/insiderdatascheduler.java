package com.stokr.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Daily insider trading data fetcher.
 *
 * <p>Sources:
 * <ul>
 *   <li>NSE Bulk Deals API: disclosed same-day, minimum ₹10Cr+ single trade</li>
 *   <li>NSE Block Deals API: negotiated trades, minimum ₹5Cr+</li>
 *   <li>BSE Insider Trading: promoter/director disclosures (2-day lag)</li>
 * </ul>
 *
 * <p>Stores results in memory as a signal map. The strategy plugins read this
 * via MarketContext extras to know which stocks have recent promoter buying.
 *
 * <p>Runs every weekday at 9:05 AM (before market open).
 */
@Slf4j
@Service
public class InsiderDataScheduler {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // In-memory cache: symbol -> insider buy details
    private final Map<String, InsiderBuySignal> activeSignals = new LinkedHashMap<>();
    private volatile LocalDate lastFetchDate = null;

    @Value("${insider.days-lookback:3}")
    private int lookbackDays = 3;

    // Known promoter/senior management names (partial, case-insensitive)
    private static final Set<String> PROMOTER_KEYWORDS = Set.of(
        "PROMOTER", "DIRECTOR", "PROMOTER GROUP", "PROMOTERS",
        "CHAIRMAN", "MANAGING DIRECTOR", "MD ", "CEO", "CFO",
        "WHOLE TIME DIRECTOR", "WTD", "JOINT MD", "EXECUTIVE DIRECTOR"
    );

    // Known institutional/acquirer names that we want (partial match)
    private static final Set<String> SMART_MONEY_KEYWORDS = Set.of(
        "RADHAKISHAN DAMANI", "RAKESH JHUNJHUNWALA", "ASHISH KACHOLIA",
        "DOLAT", "GRAVITON", "SOCIETE GENERALE", "GOLDMAN SACHS",
        "MORGAN STANLEY", "ABU DHABI", "GOVERNMENT OF SINGAPORE",
        "LIFE INSURANCE CORPORATION", "LIC", "SBI MUTUAL",
        "HDFC MUTUAL", "MOTILAL OSWAL", "MARCELLUS"
    );

    public InsiderDataScheduler(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Fetch at 9:05 AM every weekday.
     */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void fetchDailyInsiderData() {
        log.info("Fetching insider trading data (lookback={}d)...", lookbackDays);
        try {
            int count = fetchNseBulkDeals();
            log.info("Insider data updated: {} active signals", count);
        } catch (Exception e) {
            log.error("Failed to fetch insider data: {}", e.getMessage(), e);
        }
    }

    /**
     * Fetch NSE bulk deals and block deals.
     */
    private int fetchNseBulkDeals() {
        int totalFound = 0;
        activeSignals.clear();

        // Try NSE bulk deals API
        try {
            String bulkJson = restClient.get()
                .uri("https://www.nseindia.com/api/bulk-deal")
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .retrieve()
                .body(String.class);

            if (bulkJson != null) {
                JsonNode root = objectMapper.readTree(bulkJson);
                if (root.isArray()) {
                    for (JsonNode deal : root) {
                        InsiderBuySignal signal = parseDeal(deal, "BULK");
                        if (signal != null) {
                            activeSignals.put(signal.symbol(), signal);
                            totalFound++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("NSE bulk deals unavailable: {}", e.getMessage());
        }

        // Try NSE block deals API
        try {
            String blockJson = restClient.get()
                .uri("https://www.nseindia.com/api/block-deal")
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .retrieve()
                .body(String.class);

            if (blockJson != null) {
                JsonNode root = objectMapper.readTree(blockJson);
                if (root.isArray()) {
                    for (JsonNode deal : root) {
                        InsiderBuySignal signal = parseDeal(deal, "BLOCK");
                        if (signal != null && !activeSignals.containsKey(signal.symbol())) {
                            activeSignals.put(signal.symbol(), signal);
                            totalFound++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("NSE block deals unavailable: {}", e.getMessage());
        }

        lastFetchDate = LocalDate.now();
        return totalFound;
    }

    private InsiderBuySignal parseDeal(JsonNode deal, String type) {
        try {
            String symbol = deal.has("symbol") ? deal.get("symbol").asText() :
                deal.has("SYMBOL") ? deal.get("SYMBOL").asText() : null;
            if (symbol == null || symbol.isBlank()) return null;

            String buyerName = deal.has("clientName") ? deal.get("clientName").asText() :
                deal.has("CLIENT_NAME") ? deal.get("CLIENT_NAME").asText() :
                deal.has("buyerName") ? deal.get("buyerName").asText() : "";
            if (buyerName.isBlank()) return null;

            // Check if buyer is a promoter/insider
            String buyerUpper = buyerName.toUpperCase();
            boolean isInsider = false;
            for (String kw : PROMOTER_KEYWORDS) {
                if (buyerUpper.contains(kw)) { isInsider = true; break; }
            }
            // Also check smart money
            if (!isInsider) {
                for (String kw : SMART_MONEY_KEYWORDS) {
                    if (buyerUpper.contains(kw)) { isInsider = true; break; }
                }
            }
            if (!isInsider) return null;

            // Must be BUY
            String action = deal.has("buySell") ? deal.get("buySell").asText() :
                deal.has("ACTION") ? deal.get("ACTION").asText() : "BUY";
            if (action.toUpperCase().contains("SELL")) return null;

            double price = deal.has("tradePrice") ? deal.get("tradePrice").asDouble() :
                deal.has("PRICE") ? deal.get("PRICE").asDouble() : 0;
            long qty = deal.has("tradeQty") ? deal.get("tradeQty").asLong() :
                deal.has("QUANTITY") ? deal.get("QUANTITY").asLong() : 0;
            double amount = price * qty;

            if (amount < 500_000) return null; // Minimum ₹5L

            long volume = qty;
            if (deal.has("totalTradedVolume")) {
                volume = deal.get("totalTradedVolume").asLong();
            }

            return new InsiderBuySignal(
                symbol, buyerName, type,
                BigDecimal.valueOf(price), BigDecimal.valueOf(amount),
                volume, LocalDate.now()
            );
        } catch (Exception e) {
            return null;
        }
    }

    /** Check if a given symbol has active insider buying. */
    public InsiderBuySignal getSignal(String symbol) {
        return activeSignals.get(symbol);
    }

    /** Get all active signals for strategy evaluation. */
    public Map<String, InsiderBuySignal> getAllActiveSignals() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(activeSignals));
    }

    public int getActiveSignalCount() { return activeSignals.size(); }
    public LocalDate getLastFetchDate() { return lastFetchDate; }

    /**
     * Insider buy signal record.
     */
    public record InsiderBuySignal(
        String symbol,
        String buyerName,
        String dealType,
        BigDecimal price,
        BigDecimal amount,
        long volume,
        LocalDate date
    ) {
        public boolean isRecent(int days) {
            return date != null && !date.isBefore(LocalDate.now().minusDays(days));
        }
    }
}
