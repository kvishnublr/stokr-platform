package com.stokr.broker.kite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.broker.model.BrokerPositionDetail;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZerodhaKitePositionsParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseDetails_mergesNetAndDay() throws Exception {
        String json = """
                {
                  "status": "success",
                  "data": {
                    "net": [{
                      "exchange": "NSE",
                      "tradingsymbol": "ITC",
                      "quantity": 10,
                      "average_price": 450.5,
                      "realised_pnl": 12.3,
                      "unrealised_pnl": -4.2,
                      "product": "MIS"
                    }],
                    "day": []
                  }
                }
                """;
        List<BrokerPositionDetail> rows = ZerodhaKitePositionsParser.parseDetails(mapper.readTree(json));
        assertEquals(1, rows.size());
        BrokerPositionDetail d = rows.getFirst();
        assertEquals("NSE:ITC", d.symbolKey());
        assertEquals(0, d.quantity().compareTo(BigDecimal.TEN));
        assertEquals(0, d.averagePrice().compareTo(new BigDecimal("450.5")));
        assertTrue(d.product().contains("MIS"));
    }

    @Test
    void parseDetails_includesZeroQtyWithDayPnl() throws Exception {
        String json = """
                {
                  "status": "success",
                  "data": {
                    "net": [],
                    "day": [{
                      "exchange": "NSE",
                      "tradingsymbol": "ITC",
                      "quantity": 0,
                      "average_price": 0,
                      "realised_pnl": -0.45,
                      "unrealised_pnl": 0,
                      "product": "MIS"
                    }]
                  }
                }
                """;
        List<BrokerPositionDetail> rows = ZerodhaKitePositionsParser.parseDetails(mapper.readTree(json));
        assertEquals(1, rows.size());
        assertEquals(0, rows.getFirst().quantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, rows.getFirst().realisedPnl().compareTo(new BigDecimal("-0.45")));
    }
}
