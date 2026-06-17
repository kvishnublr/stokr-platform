package com.stokr.marketdata.chartink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChartinkAlertParser {

    private final ObjectMapper objectMapper;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("[h:mm a][hh:mm a][H:mm][HH:mm]", Locale.ENGLISH);

    public ChartinkAlert parse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);

            String scanName = safeText(root, "scan_name");
            String alertName = safeText(root, "alert_name");
            String scanUrl = safeText(root, "scan_url");
            String triggeredAtRaw = safeText(root, "triggered_at");
            String stocksRaw = safeText(root, "stocks");
            String pricesRaw = safeText(root, "trigger_prices");

            Instant triggeredAt = parseTriggeredAt(triggeredAtRaw);
            List<String> stocks = parseCommaList(stocksRaw);
            List<BigDecimal> prices = parsePrices(pricesRaw, stocks.size());

            return new ChartinkAlert(
                    scanName,
                    alertName,
                    scanUrl,
                    triggeredAt,
                    triggeredAtRaw,
                    stocks,
                    prices,
                    rawJson
            );
        } catch (Exception e) {
            log.error("chartink.parser.failed raw={}", rawJson, e);
            return new ChartinkAlert(
                    null, null, null, Instant.now(), null,
                    Collections.emptyList(), Collections.emptyList(), rawJson
            );
        }
    }

    public List<ChartinkAlert.StockAlert> toStockAlerts(ChartinkAlert alert) {
        if (alert.stocks().isEmpty()) {
            return Collections.emptyList();
        }
        List<ChartinkAlert.StockAlert> result = new ArrayList<>();
        for (int i = 0; i < alert.stocks().size(); i++) {
            String symbol = alert.stocks().get(i).trim();
            if (symbol.isBlank()) continue;
            BigDecimal price = i < alert.triggerPrices().size()
                    ? alert.triggerPrices().get(i)
                    : BigDecimal.ZERO;
            result.add(new ChartinkAlert.StockAlert(
                    symbol, price, alert.scanName(), alert.triggeredAt()
            ));
        }
        return result;
    }

    private String safeText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && !node.isNull() ? node.asText("").trim() : "";
    }

    private Instant parseTriggeredAt(String raw) {
        if (raw == null || raw.isBlank()) return Instant.now();
        try {
            LocalTime time = LocalTime.parse(raw.toUpperCase(Locale.ENGLISH), TIME_FORMATTER);
            Instant now = Instant.now();
            java.time.ZonedDateTime todayMarket = now.atZone(IST)
                    .toLocalDate()
                    .atTime(time)
                    .atZone(IST);
            if (todayMarket.toInstant().isAfter(now)) {
                return todayMarket.toInstant();
            }
            return todayMarket.toInstant();
        } catch (DateTimeParseException e) {
            log.warn("chartink.parser.time_fallback raw={}", raw);
            return Instant.now();
        }
    }

    private List<String> parseCommaList(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private List<BigDecimal> parsePrices(String raw, int expectedCount) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        List<BigDecimal> prices = new ArrayList<>();
        String[] parts = raw.split(",");
        for (String p : parts) {
            try {
                prices.add(new BigDecimal(p.trim()));
            } catch (NumberFormatException e) {
                prices.add(BigDecimal.ZERO);
            }
        }
        return prices;
    }
}
