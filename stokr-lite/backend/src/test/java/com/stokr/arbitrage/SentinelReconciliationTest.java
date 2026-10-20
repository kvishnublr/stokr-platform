package com.stokr.arbitrage;

import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerAccountRepository;
import com.stokr.broker.BrokerAdapter;
import com.stokr.broker.BrokerPosition;
import com.stokr.broker.BrokerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Sentinel must never close a live DB position that is still open at the broker. */
class SentinelReconciliationTest {

    private LivePositionRepository positions;
    private BrokerAccountRepository accounts;
    private BrokerService brokers;
    private BrokerAdapter motilal;
    private TradeSupervisorService sentinel;
    private LivePosition pos;

    private static BrokerPosition held(String symbol, int qty) {
        return new BrokerPosition(symbol, "NSEFO", qty, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, "NORMAL");
    }

    @BeforeEach
    void setUp() {
        positions = mock(LivePositionRepository.class);
        accounts = mock(BrokerAccountRepository.class);
        brokers = mock(BrokerService.class);
        motilal = mock(BrokerAdapter.class);
        sentinel = new TradeSupervisorService(positions, accounts, brokers, mock(OptionChainService.class),
                mock(OptionArbOpportunityRepository.class), mock(AutoExecSettingRepository.class));

        pos = new LivePosition();
        pos.setId(7L);
        pos.setStatus("OPEN");
        pos.setBroker("MOTILALOSWAL");
        pos.setUnderlying("NIFTY");
        pos.setStrategyType("IRON_CONDOR");
        pos.setLegs(List.of(Map.of("symbol", "NIFTY26OCT25000CE", "side", "SELL", "qty", 1, "price", 100.0)));
        when(positions.findAllOpen()).thenReturn(List.of(pos));

        BrokerAccount a1 = new BrokerAccount(); a1.setId(1L); a1.setAccessToken("t1");
        BrokerAccount a2 = new BrokerAccount(); a2.setId(2L); a2.setAccessToken("t2");
        when(accounts.findByBrokerNameAndStatus(eq("MOTILALOSWAL"), eq("ACTIVE"))).thenReturn(List.of(a1, a2));
        when(accounts.findByBrokerNameAndStatus(eq("ZERODHA"), eq("ACTIVE"))).thenReturn(List.of());
        when(accounts.findByBrokerNameAndStatus(eq("NAVIA"), eq("ACTIVE"))).thenReturn(List.of());
        when(brokers.getAdapter("MOTILALOSWAL")).thenReturn(motilal);
    }

    @Test
    void motilalNamesMatchKiteStoredLegs() {
        // Motilal reports "NIFTY 27-Oct-2026 CE 25000"; the leg is stored as NIFTY26OCT25000CE.
        when(motilal.getPositions("t1")).thenReturn(List.of(held("NIFTY 27-Oct-2026 CE 25000", -65)));
        when(motilal.getPositions("t2")).thenReturn(List.of());
        for (int i = 0; i < 5; i++) sentinel.reconcileBrokerPositions(true, 3);
        assertEquals("OPEN", pos.getStatus());
        verify(positions, never()).save(any());
    }

    @Test
    void positionInAnotherAccountIsFound() {
        when(motilal.getPositions("t1")).thenReturn(List.of(held("BANKNIFTY 27-Oct-2026 PE 54000", 30)));
        when(motilal.getPositions("t2")).thenReturn(List.of(held("NIFTY 27-Oct-2026 CE 25000", -65)));
        for (int i = 0; i < 5; i++) sentinel.reconcileBrokerPositions(true, 3);
        assertEquals("OPEN", pos.getStatus());
    }

    @Test
    void failedOrEmptyFetchNeverCloses() {
        // Adapters return an empty list when the API call fails -- that is not "flat".
        when(motilal.getPositions(anyString())).thenReturn(List.of());
        for (int i = 0; i < 10; i++) sentinel.reconcileBrokerPositions(true, 3);
        assertEquals("OPEN", pos.getStatus());
        verify(positions, never()).save(any());
    }

    @Test
    void reallyMissingClosesOnlyAfterThreeConsecutiveChecks() {
        when(motilal.getPositions("t1")).thenReturn(List.of(held("BANKNIFTY 27-Oct-2026 PE 54000", 30)));
        when(motilal.getPositions("t2")).thenReturn(List.of());
        sentinel.reconcileBrokerPositions(true, 3);
        sentinel.reconcileBrokerPositions(true, 3);
        assertEquals("OPEN", pos.getStatus());
        sentinel.reconcileBrokerPositions(true, 3);
        assertEquals("CLOSED", pos.getStatus());
        assertEquals("RECONCILED_FLAT_AT_BROKER", pos.getExitReason());
    }

    @Test
    void aMissInBetweenResetsTheCount() {
        when(motilal.getPositions("t2")).thenReturn(List.of());
        when(motilal.getPositions("t1"))
                .thenReturn(List.of(held("BANKNIFTY 27-Oct-2026 PE 54000", 30)))
                .thenReturn(List.of(held("BANKNIFTY 27-Oct-2026 PE 54000", 30)))
                .thenReturn(List.of(held("NIFTY 27-Oct-2026 CE 25000", -65)))
                .thenReturn(List.of(held("BANKNIFTY 27-Oct-2026 PE 54000", 30)));
        for (int i = 0; i < 4; i++) sentinel.reconcileBrokerPositions(true, 3);
        assertEquals("OPEN", pos.getStatus());
    }
}
