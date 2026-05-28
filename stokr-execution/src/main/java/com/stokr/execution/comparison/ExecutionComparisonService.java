package com.stokr.execution.comparison;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionComparisonService {

    private final ExecutionComparisonMetricsRepository metricsRepository;

    @Transactional
    public void recordPairDispatched(UUID signalId, UUID liveOrderId, UUID paperOrderId,
                                      String strategyKey, String symbol) {
        recordPairDispatched(signalId, liveOrderId, paperOrderId, strategyKey, symbol, null, null);
    }

    @Transactional
    public void recordPairDispatched(UUID signalId, UUID liveOrderId, UUID paperOrderId,
                                      String strategyKey, String symbol,
                                      BigDecimal liveQty, BigDecimal paperQty) {
        ExecutionComparisonMetrics m = new ExecutionComparisonMetrics();
        m.setSignalId(signalId);
        m.setLiveOrderId(liveOrderId);
        m.setPaperOrderId(paperOrderId);
        m.setStrategyKey(strategyKey);
        m.setSymbol(symbol);
        m.setLiveQuantity(liveQty);
        m.setPaperQuantity(paperQty);
        if (liveQty != null && paperQty != null) {
            m.setQuantityDrift(liveQty.subtract(paperQty).abs());
        }
        metricsRepository.save(m);
        log.debug("comparison.pair_dispatched signalId={} live={} paper={} qty={}/{}",
                signalId, liveOrderId, paperOrderId, liveQty, paperQty);
    }

    @Transactional
    public void onLegFilled(UUID signalId, boolean isLive, BigDecimal fillPrice, long latencyMs) {
        metricsRepository.findBySignalIdAndDeletedFalse(signalId).ifPresent(m -> {
            if (isLive) {
                m.setLiveFillPrice(fillPrice);
                m.setLiveLatencyMs(latencyMs);
            } else {
                m.setPaperFillPrice(fillPrice);
                m.setPaperLatencyMs(latencyMs);
            }
            computeDivergence(m);
            metricsRepository.save(m);
        });
    }

    private void computeDivergence(ExecutionComparisonMetrics m) {
        if (m.getLiveFillPrice() == null || m.getPaperFillPrice() == null) return;
        if (m.getPaperFillPrice().compareTo(BigDecimal.ZERO) == 0) return;
        BigDecimal divergence = m.getLiveFillPrice().subtract(m.getPaperFillPrice())
                .divide(m.getPaperFillPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        m.setSlippageDivergencePct(divergence);
        log.info("comparison.divergence signalId={} live={} paper={} divergencePct={}",
                m.getSignalId(), m.getLiveFillPrice(), m.getPaperFillPrice(), divergence);
    }
}
