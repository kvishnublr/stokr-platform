package com.stokr.bootstrap.portfolio;

import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.execution.broker.BrokerPositionTruthSyncState;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.dto.PortfolioExposureDto;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.oms.repository.PortfolioDailySummaryRepository;
import com.stokr.oms.repository.PortfolioPnlSnapshotRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrokerAwarePortfolioQueryServiceTest {

    @Mock
    private PortfolioPositionRepository positionRepository;
    @Mock
    private PortfolioPnlSnapshotRepository pnlSnapshotRepository;
    @Mock
    private PortfolioDailySummaryRepository dailySummaryRepository;
    @Mock
    private OmsTradeRepository tradeRepository;
    @Mock
    private BrokerPositionTruthService brokerPositionTruthService;

    @InjectMocks
    private BrokerAwarePortfolioQueryService service;

    @Test
    void exposure_prefersBrokerQtyWhenConnected() {
        UUID userId = UUID.randomUUID();

        PortfolioPosition infyOms = new PortfolioPosition();
        infyOms.setUserId(userId);
        infyOms.setSymbol("INFY");
        infyOms.setQuantity(new BigDecimal("4"));
        infyOms.setAvgPrice(new BigDecimal("1500"));

        when(positionRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(List.of(infyOms));
        when(tradeRepository.sumNotionalByBrokerVendor(userId)).thenReturn(List.of());

        BrokerPositionTruthSnapshot snap = new BrokerPositionTruthSnapshot(
                BrokerPositionTruthSyncState.MISMATCH,
                Instant.now(),
                12L,
                true,
                List.of(new BrokerPositionTruthSnapshot.BrokerTruthPositionRow(
                        "NSE:INFY",
                        BigDecimal.ONE,
                        new BigDecimal("4"),
                        new BigDecimal("1480"),
                        BigDecimal.ZERO,
                        new BigDecimal("-20"),
                        "CNC",
                        BrokerPositionTruthSyncState.MISMATCH.name()
                )),
                List.of(),
                Set.of(),
                Set.of("NSE:INFY"),
                0,
                "1 reconciliation item(s)"
        );
        when(brokerPositionTruthService.snapshot(userId)).thenReturn(snap);

        PortfolioExposureDto exposure = service.exposure(userId);

        assertThat(exposure.bySymbol()).hasSize(1);
        PortfolioExposureDto.SymbolExposure row = exposure.bySymbol().getFirst();
        assertThat(row.symbol()).isEqualTo("INFY");
        assertThat(row.quantity()).isEqualByComparingTo("1");
        assertThat(row.omsQuantity()).isEqualByComparingTo("4");
        assertThat(row.quantitySource()).isEqualTo("BROKER");
        assertThat(row.parityState()).isEqualTo("MISMATCH");
    }

    @Test
    void exposure_usesBrokerTruthWhenConnectedAndFlat() {
        UUID userId = UUID.randomUUID();

        when(positionRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(List.of());
        when(tradeRepository.sumNotionalByBrokerVendor(userId)).thenReturn(List.of());

        BrokerPositionTruthSnapshot snap = new BrokerPositionTruthSnapshot(
                BrokerPositionTruthSyncState.VERIFIED,
                Instant.now(),
                5L,
                true,
                List.of(),
                List.of(),
                Set.of(),
                Set.of(),
                0,
                "flat"
        );
        when(brokerPositionTruthService.snapshot(userId)).thenReturn(snap);

        PortfolioExposureDto exposure = service.exposure(userId);

        assertThat(exposure.bySymbol()).isEmpty();
    }

    @Test
    void exposure_fallsBackToOmsWhenBrokerDisconnected() {
        UUID userId = UUID.randomUUID();

        PortfolioPosition infyOms = new PortfolioPosition();
        infyOms.setUserId(userId);
        infyOms.setSymbol("INFY");
        infyOms.setQuantity(new BigDecimal("4"));
        infyOms.setAvgPrice(new BigDecimal("1500"));

        when(positionRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(List.of(infyOms));
        when(tradeRepository.sumNotionalByBrokerVendor(userId)).thenReturn(List.of());
        when(brokerPositionTruthService.snapshot(userId))
                .thenReturn(BrokerPositionTruthSnapshot.empty(false));

        PortfolioExposureDto exposure = service.exposure(userId);

        assertThat(exposure.bySymbol()).hasSize(1);
        assertThat(exposure.bySymbol().getFirst().quantity()).isEqualByComparingTo("4");
        assertThat(exposure.bySymbol().getFirst().quantitySource()).isEqualTo("OMS");
    }
}
