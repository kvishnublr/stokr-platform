package com.stokr.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure parsing / mapping logic of the Motilal Oswal MO API adapter. */
class MotilalOswalMoApiTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void scripMasterIndexesKiteStyleSymbolsWithLot() throws Exception {
        String csv = """
            exchange,exchangename,scripcode,scripname,marketlot,scripshortname
            2,NSEFO,45123,NIFTY 29-Sep-2026 CE 23400,65,NIFTY
            2,NSEFO,45124,NIFTY 27-Oct-2026 PE 22800.00,65,NIFTY
            """;
        Map<String, MotilalOswalAdapter.Scrip> m = MotilalOswalAdapter.parseScripMaster(csv, "NSEFO");
        assertEquals(new MotilalOswalAdapter.Scrip(45123, 65), m.get("NSEFO|NIFTY 29-SEP-2026 CE 23400"));
        assertEquals(45123, m.get("NSEFO|NIFTY2692923400CE").code(), "Kite weekly symbol");
        assertEquals(45123, m.get("NSEFO|NIFTY26SEP23400CE").code(), "Kite monthly symbol");
        assertEquals(45124, m.get("NSEFO|NIFTY26O2722800PE").code(), "October uses month code O");
        assertEquals(45124, m.get("NSEFO|NIFTY26OCT22800PE").code());
    }

    @Test
    void kiteAliasesIgnoreNonOptionNames() {
        assertEquals(List.of(), MotilalOswalAdapter.kiteAliases("NIFTY"));
        assertEquals(List.of(), MotilalOswalAdapter.kiteAliases("NIFTY FUT X Y"));
    }

    @Test
    void quantityIsSentInWholeLots() {
        assertEquals(2, MotilalOswalAdapter.lotsFor(130, 65));
        assertEquals(1, MotilalOswalAdapter.lotsFor(65, 65));
        assertNull(MotilalOswalAdapter.lotsFor(100, 65), "partial lot must be refused, not rounded");
        assertNull(MotilalOswalAdapter.lotsFor(65, 0), "unknown lot size must be refused");
    }

    @Test
    void marginReadsRow103OrAvailableMarginRow() throws Exception {
        assertEquals(1250000.5, MotilalOswalAdapter.parseAvailableMargin(json("""
            [{"srno":101,"particulars":"Cash","amount":900000},
             {"srno":103,"particulars":"Total Available Margin","amount":"1250000.50"}]""")), 1e-9);
        assertEquals(800000, MotilalOswalAdapter.parseAvailableMargin(json("""
            [{"srno":1,"particulars":"Total Available Margin","amount":800000}]""")), 1e-9);
        assertNull(MotilalOswalAdapter.parseAvailableMargin(json("[{\"srno\":1,\"particulars\":\"Collateral\",\"amount\":5}]")));
        assertEquals(42, MotilalOswalAdapter.parseAvailableMargin(json("{\"cashavailable\":\"42\"}")), 1e-9);
    }

    @Test
    void positionsNetQuantityAndAverage() throws Exception {
        List<BrokerPosition> ps = MotilalOswalAdapter.parsePositions(json("""
            [{"symbol":"NIFTY 29-Sep-2026 CE 23400","exchange":"NSEFO","buyquantity":0,"sellquantity":65,
              "buyamount":"0","sellamount":"1605.5","LTP":"20.1","marktomarket":"299","bookedprofitloss":"0",
              "productname":"NORMAL"},
             {"symbol":"FLAT","buyquantity":65,"sellquantity":65,"buyamount":"1","sellamount":"1"}]"""));
        assertEquals(1, ps.size());
        assertEquals(-65, ps.get(0).quantity());
        assertEquals(24.7, ps.get(0).avgPrice().doubleValue(), 1e-4);
    }

    @Test
    void orderStatusMapping() {
        assertEquals("OPEN", MotilalOswalAdapter.mapOrderStatus("Confirm"));
        assertEquals("COMPLETE", MotilalOswalAdapter.mapOrderStatus("Traded"));
        assertEquals("PARTIAL", MotilalOswalAdapter.mapOrderStatus("Partially Traded"));
        assertEquals("CANCELLED", MotilalOswalAdapter.mapOrderStatus("Cancel"));
        assertEquals("REJECTED", MotilalOswalAdapter.mapOrderStatus("Rejected"));
        assertEquals("REJECTED", MotilalOswalAdapter.mapOrderStatus("Error"));
        assertEquals("UNKNOWN", MotilalOswalAdapter.mapOrderStatus(null));
    }

    @Test
    void sessionErrorsAreRecognised() throws Exception {
        assertTrue(MotilalOswalAdapter.isSessionError(json("{\"status\":\"ERROR\",\"message\":\"Invalid Authorization token\"}")));
        assertTrue(MotilalOswalAdapter.isSessionError(json("{\"status\":\"ERROR\",\"message\":\"Session expired, login again\"}")));
        assertFalse(MotilalOswalAdapter.isSessionError(json("{\"status\":\"ERROR\",\"message\":\"Insufficient margin\"}")));
    }

    @Test
    void sessionExpiresBeforeNextOpenAndWithin8h() {
        ZonedDateTime afternoon = ZonedDateTime.parse("2026-09-28T16:00:00+05:30[Asia/Kolkata]");
        assertEquals(afternoon.plusHours(8).toInstant().toEpochMilli(), MotilalOswalAdapter.sessionExpiry(afternoon));
        ZonedDateTime lateNight = ZonedDateTime.parse("2026-09-28T23:30:00+05:30[Asia/Kolkata]");
        assertEquals(ZonedDateTime.parse("2026-09-29T07:30:00+05:30[Asia/Kolkata]").toInstant().toEpochMilli(),
            MotilalOswalAdapter.sessionExpiry(lateNight), "8h cap");
        ZonedDateTime early = ZonedDateTime.parse("2026-09-29T06:00:00+05:30[Asia/Kolkata]");
        assertEquals(ZonedDateTime.parse("2026-09-29T08:30:00+05:30[Asia/Kolkata]").toInstant().toEpochMilli(),
            MotilalOswalAdapter.sessionExpiry(early), "pre-open login is renewed at 08:30");
    }
}
