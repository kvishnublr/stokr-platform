package com.stokr.intraday.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvTradePlanEnricherTest {

    private final AdvTradePlanEnricher enricher = new AdvTradePlanEnricher();

    @Test
    void enrichesBuyGapFillWithStopBelowEntry() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", "SBIN");
        row.put("side", "BUY");
        row.put("setupType", "GAP_FILL");
        row.put("ltp", new BigDecimal("100.00"));
        row.put("executionStatus", "INTELLIGENCE_ONLY");

        enricher.enrich(row);

        assertNotNull(row.get("stopLoss"));
        assertNotNull(row.get("targetPrice"));
        assertTrue(((BigDecimal) row.get("stopLoss")).compareTo(new BigDecimal("100")) < 0);
        assertTrue(((BigDecimal) row.get("targetPrice")).compareTo(new BigDecimal("100")) > 0);
        assertTrue(String.valueOf(row.get("exitPlan")).contains("not sent to OMS"));
        assertTrue(String.valueOf(row.get("tradeCall")).startsWith("BUY SBIN"));
    }
}
