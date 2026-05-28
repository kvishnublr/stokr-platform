package com.stokr.broker.kite;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZerodhaKiteInstrumentResolverTest {

    @Test
    void matchesMainCrudeFutureExcludesMini() {
        ZerodhaKiteInstrumentResolver.InstrumentRow main = row("CRUDEOIL26MAYFUT", "FUTCOM", LocalDate.now().plusDays(10));
        ZerodhaKiteInstrumentResolver.InstrumentRow mini = row("CRUDEOILM26MAYFUT", "FUTCOM", LocalDate.now().plusDays(10));
        assertTrue(ZerodhaKiteInstrumentResolver.matchesFutureRoot(main, "CRUDEOIL", false));
        assertFalse(ZerodhaKiteInstrumentResolver.matchesFutureRoot(mini, "CRUDEOIL", false));
        assertTrue(ZerodhaKiteInstrumentResolver.matchesFutureRoot(mini, "CRUDEOILM", true));
    }

    @Test
    void parseSymbolExchangeHandlesMcxPrefix() {
        String[] parsed = ZerodhaKiteInstrumentResolver.parseSymbolExchange("MCX:CRUDEOIL", null);
        assertEquals("MCX", parsed[0]);
        assertEquals("CRUDEOIL", parsed[1]);
    }

    private static ZerodhaKiteInstrumentResolver.InstrumentRow row(
            String tradingsymbol, String type, LocalDate expiry) {
        ZerodhaKiteInstrumentResolver.InstrumentRow row = new ZerodhaKiteInstrumentResolver.InstrumentRow();
        row.tradingsymbol = tradingsymbol;
        row.instrumentType = type;
        row.expiry = expiry;
        return row;
    }
}
