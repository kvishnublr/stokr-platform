package com.stokr.chartink;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO for Chartink FREE/PRO webhook payloads.
 * Chartink sends batch format with comma-separated stocks and prices.
 */
public record ChartinkWebhookRequest(
        @JsonProperty("stocks") String stocks,
        @JsonProperty("trigger_prices") String triggerPrices,
        @JsonProperty("triggered_at") String triggeredAt,
        @JsonProperty("scan_name") String scanName,
        @JsonProperty("scan_url") String scanUrl,
        @JsonProperty("alert_name") String alertName,
        @JsonProperty("webhook_url") String webhookUrl
) {
    public List<StockHit> parseHits() {
        List<StockHit> hits = new ArrayList<>();
        if (stocks == null || stocks.isBlank()) return hits;

        String[] symbols = stocks.split("\\s*,\\s*");
        String[] prices = (triggerPrices != null)
                ? triggerPrices.split("\\s*,\\s*")
                : new String[0];

        for (int i = 0; i < symbols.length; i++) {
            String symbol = symbols[i].trim().toUpperCase();
            if (symbol.isEmpty()) continue;
            BigDecimal price = null;
            if (i < prices.length) {
                try {
                    price = new BigDecimal(prices[i].trim());
                } catch (NumberFormatException ignored) {}
            }
            hits.add(new StockHit(symbol, price));
        }
        return hits;
    }

    public record StockHit(String symbol, BigDecimal triggerPrice) {}
}
