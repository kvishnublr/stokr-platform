package com.stokr.backtest.service;

import com.stokr.backtest.metrics.MetricsCalculator;
import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplayValidationServiceTest {

    @Mock
    StrategySignalRepository signalRepository;
    @Mock
    OmsExecutionRepository executionRepository;

    @InjectMocks
    ReplayValidationService replayValidationService;

    @Test
    void validateRun_replayHashStableForSameRunData() {
        UUID runId = UUID.randomUUID();
        when(signalRepository.countByBacktestRunId(runId)).thenReturn(1L);
        OmsExecution e = execution("h1", 1L, BigDecimal.TEN, new BigDecimal("100"));
        when(executionRepository.findAllForBacktestRunOrdered(runId)).thenReturn(List.of(e));

        ReplayValidationService.ReplayValidationReport a = replayValidationService.validateRun(runId);
        ReplayValidationService.ReplayValidationReport b = replayValidationService.validateRun(runId);

        assertThat(a.replayHash()).isEqualTo(b.replayHash());
        assertThat(a.replayHash()).isNotBlank();
        assertThat(a.strategySignalCount()).isEqualTo(1L);
        assertThat(a.executionEventCount()).isEqualTo(1L);
    }

    @Test
    void validateRun_hashChangesWhenExecutionSequenceChanges() {
        UUID runId = UUID.randomUUID();
        when(signalRepository.countByBacktestRunId(runId)).thenReturn(1L);
        OmsExecution e1 = execution("h1", 1L, BigDecimal.TEN, new BigDecimal("100"));
        when(executionRepository.findAllForBacktestRunOrdered(runId)).thenReturn(List.of(e1));
        String hash1 = replayValidationService.validateRun(runId).replayHash();

        OmsExecution e2 = execution("h1", 2L, BigDecimal.TEN, new BigDecimal("100"));
        when(executionRepository.findAllForBacktestRunOrdered(runId)).thenReturn(List.of(e2));
        String hash2 = replayValidationService.validateRun(runId).replayHash();

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void metricsCalculator_sharpeZeroWhenReturnsConstant() {
        MetricsCalculator mc = new MetricsCalculator();
        BigDecimal z = mc.sharpe(List.of(BigDecimal.ONE, BigDecimal.ONE), BigDecimal.ZERO);
        assertThat(z).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static OmsExecution execution(String hash, long seq, BigDecimal qty, BigDecimal px) {
        OmsOrder o = new OmsOrder();
        o.setId(UUID.randomUUID());
        OmsExecution e = new OmsExecution();
        e.setOrder(o);
        e.setExecutionHash(hash);
        e.setExecutionSequence(seq);
        e.setFilledQty(qty);
        e.setAvgPrice(px);
        return e;
    }
}
