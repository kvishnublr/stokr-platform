package com.stokr.broker.kite;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.broker.model.BrokerPosition;
import com.stokr.broker.model.BrokerPositionDetail;

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
        return parseDetails(payload).stream()
                .filter(d -> d.quantity() != null && d.quantity().compareTo(BigDecimal.ZERO) != 0)
                .map(d -> new BrokerPosition(d.symbolKey(), d.quantity(), d.averagePrice()))
                .toList();
    }

    public static List<BrokerPositionDetail> parseDetails(JsonNode payload) {
        if (payload == null || !"success".equalsIgnoreCase(payload.path("status").asText(""))) {
            return List.of();
        }
        JsonNode data = payload.path("data");
        Map<String, BrokerPositionDetail> merged = new LinkedHashMap<>();
        mergeSectionDetails(data.path("net"), merged);
        mergeSectionDetails(data.path("day"), merged);
        return new ArrayList<>(merged.values());
    }

    private static void mergeSectionDetails(JsonNode rows, Map<String, BrokerPositionDetail> merged) {
        if (!rows.isArray()) {
            return;
        }
        for (JsonNode row : rows) {
            BrokerPositionDetail d = toDetail(row);
            if (d == null) {
                continue;
            }
            boolean openQty = d.quantity() != null && d.quantity().compareTo(BigDecimal.ZERO) != 0;
            boolean hasPnl = hasNonZeroPnl(d);
            if (!openQty && !hasPnl) {
                continue;
            }
            merged.merge(d.symbolKey(), d, ZerodhaKitePositionsParser::mergeDetail);
        }
    }

    private static BrokerPositionDetail mergeDetail(BrokerPositionDetail a, BrokerPositionDetail b) {
        return new BrokerPositionDetail(
                a.exchange() != null ? a.exchange() : b.exchange(),
                a.tradingsymbol() != null ? a.tradingsymbol() : b.tradingsymbol(),
                a.symbolKey(),
                b.quantity(),
                b.averagePrice() != null ? b.averagePrice() : a.averagePrice(),
                b.realisedPnl() != null ? b.realisedPnl() : a.realisedPnl(),
                b.unrealisedPnl() != null ? b.unrealisedPnl() : a.unrealisedPnl(),
                b.product() != null ? b.product() : a.product()
        );
    }

    private static BrokerPositionDetail toDetail(JsonNode row) {
        String exchange = row.path("exchange").asText("").trim().toUpperCase(Locale.ROOT);
        String tradingsymbol = row.path("tradingsymbol").asText("").trim().toUpperCase(Locale.ROOT);
        if (tradingsymbol.isBlank()) {
            return null;
        }
        String key = exchange.isBlank() ? tradingsymbol : exchange + ":" + tradingsymbol;
        return new BrokerPositionDetail(
                exchange,
                tradingsymbol,
                key,
                readQty(row),
                readAvg(row),
                readDecimal(row, "realised_pnl", "realized_pnl"),
                readDecimal(row, "unrealised_pnl", "unrealized_pnl"),
                row.path("product").asText("")
        );
    }

    private static BigDecimal readDecimal(JsonNode row, String... fields) {
        for (String f : fields) {
            if (row.path(f).isNumber()) {
                return BigDecimal.valueOf(row.path(f).asDouble());
            }
        }
        return null;
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

    private static boolean hasNonZeroPnl(BrokerPositionDetail d) {
        return isNonZero(d.realisedPnl()) || isNonZero(d.unrealisedPnl());
    }

    private static boolean isNonZero(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) != 0;
    }
}
