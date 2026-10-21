package com.stokr.arbitrage;

import com.stokr.broker.BrokerAccountRepository;
import com.stokr.broker.BrokerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Drives the real exit monitor (checkRolloverAt) for a paper Iron Condor:
 * SL now applies to multi-leg Smart positions (confirmed on 2 checks), price rules never
 * fire on bad quotes, and the time exit fires even when quotes are missing or stale.
 */
class ExitEngineEndToEndTest {

    private LivePositionRepository positions;
    private OptionChainService chain;
    private OptionArbAutoExecService engine;
    private LivePosition pos;
    private final Map<String, OptionChainService.OptionQuote> quotes = new HashMap<>();

    private static OptionChainService.OptionQuote q(double bid, double ask) {
        OptionChainService.OptionQuote q = new OptionChainService.OptionQuote();
        q.bid = bid; q.ask = ask; q.lastPrice = (bid + ask) / 2; q.fetchedAtMs = System.currentTimeMillis();
        return q;
    }

    private static Map<String, Object> leg(String sym, String side, double price) {
        return new LinkedHashMap<>(Map.of("symbol", sym, "side", side, "qty", 1, "price", price));
    }

    @BeforeEach
    void setUp() {
        positions = mock(LivePositionRepository.class);
        chain = mock(OptionChainService.class);
        AutoExecSettingRepository settings = mock(AutoExecSettingRepository.class);
        when(settings.findAll()).thenReturn(List.of());
        engine = new OptionArbAutoExecService(mock(OptionArbOpportunityRepository.class), positions,
                mock(BrokerService.class), mock(BrokerAccountRepository.class), chain,
                mock(TradeSupervisorService.class), settings);
        engine.init();

        // Iron Condor, 1 lot x 30: credit (100-50)+(100-50) = 100 pts = Rs 3,000; max loss Rs 10,000.
        pos = new LivePosition();
        pos.setId(42L);
        pos.setBroker("PAPER");
        pos.setStatus("OPEN");
        pos.setStrategyType("IRON_CONDOR");
        pos.setUnderlying("BANKNIFTY");
        pos.setLots(1);
        pos.setLotSize(30);
        pos.setExpiryDate(LocalDate.now().plusDays(6));
        pos.setEnteredAt(LocalDateTime.now().minusMinutes(30));
        pos.setSlPct(45.0);            // SL at Rs 4,500
        pos.setTargetPct(45.0);
        pos.setMaxLossAmount(10000.0);
        pos.setMaxProfitAmount(3000.0);
        pos.setTimeExitMinutes(75);    // 14:15
        pos.setLegs(List.of(leg("A", "SELL", 100), leg("B", "BUY", 50), leg("C", "SELL", 100), leg("D", "BUY", 50)));
        when(positions.findAllOpen()).thenReturn(List.of(pos));
        when(chain.fetchQuotes(anyList())).thenAnswer(i -> new HashMap<>(quotes));
    }

    private void marks(double aAsk, double cAsk) {
        quotes.put("A", q(aAsk - 1, aAsk));
        quotes.put("B", q(50, 51));
        quotes.put("C", q(cAsk - 1, cAsk));
        quotes.put("D", q(50, 51));
    }

    @Test
    void stopLossNowFiresForMultiLegAfterTwoConfirmedChecks() {
        marks(200, 160);                                  // loss (100+60) x 30 = Rs 4,800 >= 4,500
        engine.checkRolloverAt(LocalTime.of(11, 0));
        assertEquals("OPEN", pos.getStatus(), "first breach only arms the SL");
        engine.checkRolloverAt(LocalTime.of(11, 0));
        assertEquals("CLOSED", pos.getStatus());
        assertEquals("PER_POSITION_SL", pos.getExitReason());
        assertEquals(-4800, pos.getCurrentPnl().doubleValue(), 1);
    }

    @Test
    void oneTickBreachThatRecoversDoesNotStopOut() {
        marks(200, 160);
        engine.checkRolloverAt(LocalTime.of(11, 0));
        marks(120, 110);                                  // back to a small loss
        engine.checkRolloverAt(LocalTime.of(11, 0));
        marks(200, 160);
        engine.checkRolloverAt(LocalTime.of(11, 0));
        assertEquals("OPEN", pos.getStatus());
    }

    @Test
    void staleQuotesNeverTriggerPriceRules() {
        marks(200, 160);
        quotes.get("A").staleFallback = true;
        engine.checkRolloverAt(LocalTime.of(11, 0));
        engine.checkRolloverAt(LocalTime.of(11, 0));
        assertEquals("OPEN", pos.getStatus());
    }

    @Test
    void timeExitFiresEvenWithAMissingLegQuote() {
        marks(110, 105);
        quotes.remove("D");
        engine.checkRolloverAt(LocalTime.of(13, 0));
        assertEquals("OPEN", pos.getStatus(), "before 14:15 bad quotes just skip the position");
        engine.checkRolloverAt(LocalTime.of(14, 16));
        assertEquals("CLOSED", pos.getStatus());
        assertEquals("TIME_EXIT", pos.getExitReason());
    }

    @Test
    void targetStillWorks() {
        marks(40, 40);                                    // profit (60+60) x 30 = Rs 3,600 >= 45% of 3,000
        engine.checkRolloverAt(LocalTime.of(11, 0));
        assertEquals("CLOSED", pos.getStatus());
        assertEquals("PER_POSITION_TARGET", pos.getExitReason());
    }

    @Test
    void zeroQuoteBeforeExpiryIsNotTreatedAsWorthless() {
        marks(100, 100);
        OptionChainService.OptionQuote zero = new OptionChainService.OptionQuote();
        quotes.put("A", zero);                            // Kite returned nothing useful
        // Old behaviour booked the SELL leg as expired worthless (+Rs 3,000).
        assertEquals(0, engine.computeMultiLegPnl(pos, quotes), 1);  // leg carried at entry
    }
}
