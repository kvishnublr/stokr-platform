package com.stokr.bootstrap.feed.zerodha;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdsInstrumentResolverTest {

    @Test
    void resolveMajorPairs_picksNearestFuturesContract() throws Exception {
        String csv = """
                instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
                123,1,USDINR26JUNFUT,USDINR,0,2026-06-26,,0.0025,1,FUT,CDS-FUT,CDS
                456,2,EURINR26JUNFUT,EURINR,0,2026-06-26,,0.0025,1,FUT,CDS-FUT,CDS
                789,3,USDINR26JULFUT,USDINR,0,2026-07-31,,0.0025,1,FUT,CDS-FUT,CDS
                """;

        Map<String, Integer> pairs = CdsInstrumentResolver.resolveMajorPairs(csv);

        assertEquals(2, pairs.size());
        assertTrue(pairs.containsKey("USDINR"));
        assertTrue(pairs.containsKey("EURINR"));
        assertEquals(123, pairs.get("USDINR"));
        assertEquals(456, pairs.get("EURINR"));
    }
}
