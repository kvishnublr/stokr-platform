package com.stokr.broker.kite;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.broker.model.BrokerPosition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maps Kite {@code GET /portfolio/positions} payload to neutral broker positions. */
public final class ZerodhaKitePositionsParser {

    private ZerodhaKitePositionsParser() {
    }

    public static List<BrokerPosition> parse(JsonNode payload) {
        if (payload == null || !"success".equalsIgnoreCase(payload.path("status").asText(""))) {
            return List.of();
        }
        JsonNode data = payload.path("data");
        Map<String, BigDecimal> qtyBySymbol = new LinkedHashMap<>();
        Map<String, BigDecimal> avgBySymbol = new LinkedHashMap<>();

        mergeSection(data.path("net"), qtyBySymbol, avgBySymbol);
        mergeSection(data.path("day"), qtyBySymbol, avgBySymbol);

        List<BrokerPosition> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : qtyBySymbol.entrySet()) {
            if (e.getValue() == null || e.getValue().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            out.add(new BrokerPosition(e.getKey(), e.getValue(), avgBySymbol.get(e.getKey())));
        }
        return out;
    }

    private static void mergeSection(
            JsonNode rows,
            Map<String, BigDecimal> qtyBySymbol,
            Map<String, BigDecimal> avgBySymbol) {
        if (!rows.isArray()) {
            return;
        }
        for (JsonNode row : rows) {
            String exchange = row.path("exchange").asText("").trim().toUpperCase(Locale.ROOT);
            String tradingsymbol = row.path("tradingsymbol").asText("").trim().toUpperCase(Locale.ROOT);
            if (tradingsymbol.isBlank()) {
                continue;
            }
            String key = exchange.isBlank() ? tradingsymbol : exchange + ":" + tradingsymbol;
            BigDecimal qty = readQty(row);
            if (qty.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            qtyBySymbol.merge(key, qty, BigDecimal::add);
            BigDecimal avg = readAvg(row);
            if (avg != null) {
                avgBySymbol.put(key, avg);
            }
        }
    }

    private static BigDecimal readQty(JsonNode row) {
        if (row.path("quantity").isNumber()) {
            return BigDecimal.valueOf(row.path("quantity").asDouble());
        }
        if (row.path("net_quantity").isNumber()) {
            return BigDecimal.valueOf(row.path("net_quantity").asDouble());
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal readAvg(JsonNode row) {
        if (row.path("average_price").isNumber()) {
            return BigDecimal.valueOf(row.path("average_price").asDouble());
        }
        return null;
    }
}
