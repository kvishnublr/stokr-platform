package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing of XTS Interactive API responses (shapes per the XTS docs; numerics arrive as strings). */
class MotilalOswalXtsParsingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void balanceReadsNetMarginFromRmsSubLimits() throws Exception {
        JsonNode result = json("""
            {"BalanceList":[{"limitHeader":"ALL|ALL|ALL","limitObject":{
              "RMSSubLimits":{"cashAvailable":"250000","collateral":0,"marginUtilized":"12000",
                              "netMarginAvailable":"238000.50","marginUtilizedPercentage":"4"},
              "marginAvailable":{"CashMarginAvailable":"250000"},"AccountID":"X123"}}]}""");
        assertEquals(238000.50, MotilalOswalAdapter.parseAvailableMargin(result), 1e-9);
    }

    @Test
    void balancePrefersAggregateEntryOverSegments() throws Exception {
        JsonNode result = json("""
            {"BalanceList":[
              {"limitHeader":"NSECM|ALL|ALL","limitObject":{"RMSSubLimits":{"netMarginAvailable":"1000"}}},
              {"limitHeader":"ALL|ALL|ALL","limitObject":{"RMSSubLimits":{"netMarginAvailable":"5000"}}}]}""");
        assertEquals(5000, MotilalOswalAdapter.parseAvailableMargin(result), 1e-9);
    }

    @Test
    void balanceReturnsNullWhenNoKnownField() throws Exception {
        assertNull(MotilalOswalAdapter.parseAvailableMargin(json("{\"BalanceList\":[{\"limitHeader\":\"x\"}]}")));
        assertEquals(700, MotilalOswalAdapter.parseAvailableMargin(json("{\"netMarginAvailable\":\"700\"}")), 1e-9);
    }

    @Test
    void positionsUseStringNetQuantity() throws Exception {
        JsonNode list = json("""
            [{"TradingSymbol":"NIFTY 29SEP2026 CE 23400","ExchangeSegment":"NSEFO","ProductType":"NRML",
              "Quantity":"-65","OpenBuyQuantity":"0","OpenSellQuantity":"65",
              "BuyAveragePrice":"0","SellAveragePrice":"24.70","UnrealizedMTM":"120.5","RealizedMTM":"0"},
             {"TradingSymbol":"NIFTY 29SEP2026 PE 22800","ExchangeSegment":"NSEFO","ProductType":"NRML",
              "Quantity":"0","BuyAveragePrice":"11.4","SellAveragePrice":"12.0"}]""");
        List<BrokerPosition> ps = MotilalOswalAdapter.parsePositions(list);
        assertEquals(1, ps.size(), "flat rows are skipped");
        BrokerPosition p = ps.get(0);
        assertEquals(-65, p.quantity());
        assertEquals(24.70, p.avgPrice().doubleValue(), 1e-9, "short position uses sell average");
        assertEquals(120.5, p.unrealizedPnl().doubleValue(), 1e-9);
        assertEquals("NRML", p.productType());
    }

    @Test
    void positionsFallBackToOpenQuantitiesAndNestedShape() throws Exception {
        JsonNode list = json("""
            [{"TradingSymbol":"A","OpenBuyQuantity":"130","OpenSellQuantity":"65","BuyAveragePrice":"10"},
             {"TradingSymbol":"B","Quantity":{"BuyQuantity":65,"SellQuantity":0},"BuyAveragePrice":"5"}]""");
        List<BrokerPosition> ps = MotilalOswalAdapter.parsePositions(list);
        assertEquals(65, ps.get(0).quantity());
        assertEquals(65, ps.get(1).quantity());
    }

    @Test
    void orderStatusMapsXtsValues() {
        assertEquals("PARTIAL", MotilalOswalAdapter.mapXtsOrderStatus("PartiallyFilled"));
        assertEquals("OPEN", MotilalOswalAdapter.mapXtsOrderStatus("PendingCancel"));
        assertEquals("OPEN", MotilalOswalAdapter.mapXtsOrderStatus("Replaced"));
        assertEquals("COMPLETE", MotilalOswalAdapter.mapXtsOrderStatus("Filled"));
        assertEquals("REJECTED", MotilalOswalAdapter.mapXtsOrderStatus("Rejected"));
    }

    @Test
    void recognisesExpiredSessionResponses() throws Exception {
        assertTrue(MotilalOswalAdapter.isSessionError(json(
            "{\"type\":\"error\",\"code\":\"e-session-0002\",\"description\":\"Invalid Token\"}")));
        assertFalse(MotilalOswalAdapter.isSessionError(json(
            "{\"type\":\"error\",\"code\":\"e-rms-0001\",\"description\":\"Insufficient balance\"}")));
    }

    @Test
    void searchNeverGuessesAnotherContract() throws Exception {
        JsonNode results = json("""
            [{"ExchangeInstrumentID":111,"DisplayName":"NIFTY 29SEP2026 CE 23500"},
             {"ExchangeInstrumentID":222,"TradingSymbol":"NIFTY2692923400CE"}]""");
        assertEquals(222L, MotilalOswalAdapter.exactSearchMatch(results, "NIFTY2692923400CE"));
        assertNull(MotilalOswalAdapter.exactSearchMatch(results, "NIFTY2692923300CE"),
            "no exact match must return null, not the first result");
    }
}
