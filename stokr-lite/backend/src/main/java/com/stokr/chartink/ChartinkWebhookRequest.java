package com.stokr.chartink;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChartinkWebhookRequest {

    @JsonProperty("stocks")
    private String stocks;

    @JsonProperty("trigger_prices")
    private String triggerPrices;

    @JsonProperty("triggered_at")
    private String triggeredAt;

    @JsonProperty("scan_name")
    private String scanName;

    @JsonProperty("scan_url")
    private String scanUrl;

    @JsonProperty("alert_name")
    private String alertName;

    @JsonProperty("webhook_url")
    private String webhookUrl;

    private final Map<String, String> additionalFields = new HashMap<>();

    @JsonAnySetter
    public void setAdditionalField(String name, String value) {
        if (value != null && !value.isBlank()) {
            additionalFields.put(name, value);
        }
    }

    public String getStocks() { return stocks; }
    public String getTriggerPrices() { return triggerPrices; }
    public String getTriggeredAt() { return triggeredAt; }
    public String getScanName() { return scanName; }
    public String getScanUrl() { return scanUrl; }
    public String getAlertName() { return alertName; }
    public String getWebhookUrl() { return webhookUrl; }
    public Map<String, String> getAdditionalFields() { return additionalFields; }

    public String getField(String name) {
        if (additionalFields.containsKey(name)) return additionalFields.get(name);
        return switch (name) {
            case "stocks" -> stocks;
            case "trigger_prices" -> triggerPrices;
            case "triggered_at" -> triggeredAt;
            case "scan_name" -> scanName;
            case "scan_url" -> scanUrl;
            case "alert_name" -> alertName;
            case "webhook_url" -> webhookUrl;
            default -> null;
        };
    }

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
                try { price = new BigDecimal(prices[i].trim()); } catch (NumberFormatException ignored) {}
            }
            Map<String, String> indicators = new HashMap<>();
            for (Map.Entry<String, String> entry : additionalFields.entrySet()) {
                String[] values = entry.getValue().split("\\s*,\\s*");
                if (i < values.length && !values[i].isBlank()) {
                    indicators.put(entry.getKey(), values[i].trim());
                }
            }
            hits.add(new StockHit(symbol, price, indicators));
        }
        return hits;
    }

    public record StockHit(String symbol, BigDecimal triggerPrice, Map<String, String> indicators) {
        public StockHit(String symbol, BigDecimal triggerPrice) {
            this(symbol, triggerPrice, Map.of());
        }
    }
}
