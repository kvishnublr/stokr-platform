package com.stokr.execution.safety;

import com.stokr.risk.service.BrokerOperationalCircuitService;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BrokerDisconnectProtectionServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRIMARY_TRADER = UUID.fromString("6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4");

    @Mock
    private BrokerAccountRepository brokerAccountRepository;
    @Mock
    private PlatformBrokerFeedSessionRepository feedSessionRepository;
    @Mock
    private BrokerOperationalCircuitService brokerOperationalCircuitService;
    @Mock
    private TradingKillSwitchService killSwitchService;
    @Mock
    private PlatformMarketFeedService platformMarketFeedService;

    private BrokerDisconnectProtectionService service;

    @BeforeEach
    void setUp() {
        service = new BrokerDisconnectProtectionService(
                brokerAccountRepository,
                feedSessionRepository,
                brokerOperationalCircuitService,
                killSwitchService,
                platformMarketFeedService
        );
        ReflectionTestUtils.setField(service, "blockLive", true);
        ReflectionTestUtils.setField(service, "autoHealBeforeBlock", true);
        ReflectionTestUtils.setField(service, "primaryTraderUserIdRaw", PRIMARY_TRADER.toString());
        when(brokerOperationalCircuitService.isGlobalBrokerHalt()).thenReturn(false);
        when(feedSessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse("ZERODHA"))
                .thenReturn(Optional.of(sessionWithOpenWebsocket()));
    }

    @Test
    void resolveExecutionUserId_fallsBackToPrimaryWhenAdminHasNoBrokerRow() {
        when(brokerAccountRepository.findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
                eq(ADMIN_ID), eq("ZERODHA"))).thenReturn(Optional.empty());

        assertEquals(PRIMARY_TRADER, service.resolveExecutionUserId(ADMIN_ID));
    }

    @Test
    void traderBrokerNotDegradedWhenHealthDegradedButTokenStillValid() {
        when(brokerAccountRepository.findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
                eq(PRIMARY_TRADER), eq("ZERODHA"))).thenReturn(Optional.of(connectedDegradedWithValidToken()));

        assertFalse(service.isTraderBrokerDegraded(PRIMARY_TRADER));
        assertFalse(service.blocksLiveOrders(PRIMARY_TRADER));
    }

    @Test
    void adminDiagnosticsUsePrimaryTraderBrokerRow() {
        when(brokerAccountRepository.findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
                eq(ADMIN_ID), eq("ZERODHA"))).thenReturn(Optional.empty());
        when(brokerAccountRepository.findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
                eq(PRIMARY_TRADER), eq("ZERODHA"))).thenReturn(Optional.of(connectedHealthy()));

        assertFalse(service.blocksLiveOrders(ADMIN_ID));
        verify(platformMarketFeedService).syncPlatformTokensToTraders();
    }

    @Test
    void blocksLiveWhenTraderDisconnected() {
        when(brokerAccountRepository.findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
                eq(PRIMARY_TRADER), eq("ZERODHA"))).thenReturn(Optional.of(connectedDisconnected()));

        assertTrue(service.blocksLiveOrders(PRIMARY_TRADER));
    }

    private static com.stokr.user.domain.PlatformBrokerFeedSession sessionWithOpenWebsocket() {
        var s = new com.stokr.user.domain.PlatformBrokerFeedSession();
        s.setWebsocketState("OPEN");
        return s;
    }

    private static BrokerAccount connectedHealthy() {
        BrokerAccount a = new BrokerAccount();
        a.setStatus("CONNECTED");
        a.setHealthStatus("HEALTHY");
        a.setAccessTokenEnc("enc");
        a.setTokenExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
        return a;
    }

    private static BrokerAccount connectedDegradedWithValidToken() {
        BrokerAccount a = new BrokerAccount();
        a.setStatus("CONNECTED");
        a.setHealthStatus("DEGRADED");
        a.setAccessTokenEnc("enc");
        a.setTokenExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
        return a;
    }

    private static BrokerAccount connectedDisconnected() {
        BrokerAccount a = new BrokerAccount();
        a.setStatus("CONNECTED");
        a.setHealthStatus("DISCONNECTED");
        a.setAccessTokenEnc("enc");
        a.setTokenExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
        return a;
    }
}
