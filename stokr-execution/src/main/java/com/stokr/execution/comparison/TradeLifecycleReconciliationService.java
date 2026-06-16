package com.stokr.execution.comparison;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.lifecycle.StrategyExitTelemetry;
import com.stokr.strategy.lifecycle.StrategyExitTelemetryRepository;
import com.stokr.strategy.operational.StrategyRuntimeHealthService;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeLifecycleReconciliationService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_AWAITING_CLOSE = "AWAITING_CLOSE";
    private static final String STATUS_RECONCILED = "RECONCILED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PAPER_ONLY = "PAPER_ONLY";

    private final ExecutionComparisonMetricsRepository metricsRepository;
    private final StrategySignalRepository signalRepository;
    private final StrategyExitTelemetryRepository exitTelemetryRepository;
    private final OmsTradeRepository tradeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final StrategyRuntimeHealthService runtimeHealthService;

    @Value("${stokr.reconciliation.qty-tolerance:0.001}")
    private BigDecimal qtyTolerance;

    @Transactional
    public void onOrderFilled(OmsOrder order, BigDecimal avgFillPrice, int fillCount, long latencyMs) {
        if (order == null || order.isTestTrade() || order.getBacktestRunId() != null) {
            return;
        }
        UUID signalId = resolveSignalId(order);
        if (signalId == null) {
            return;
        }

        if (!isExitOrder(order)) {
            String strategyKey = order.getStrategyKey() != null ? order.getStrategyKey() : "UNKNOWN";
            runtimeHealthService.recordTradeOpened(strategyKey, Instant.now());
        }

        metricsRepository.findBySignalIdAndDeletedFalse(signalId).ifPresentOrElse(
                m -> applyFill(m, order, signalId, avgFillPrice, fillCount, latencyMs),
                () -> log.debug("reconciliation.no_pair signalId={} orderId={}", signalId, order.getId()));
    }

    @Transactional
    public void onPaperPositionClosed(UUID signalId) {
        if (signalId == null) {
            return;
        }
        StrategySignalEntity signal = signalRepository.findById(signalId).orElse(null);
        if (signal == null || signal.isDeleted()) {
            return;
        }

        metricsRepository.findBySignalIdAndDeletedFalse(signalId).ifPresent(m -> {
            m.setPaperPositionClosed(true);
            m.setPaperClosedAt(signal.getOutcomeTime() != null ? signal.getOutcomeTime() : Instant.now());
            m.setPaperRealizedPnl(signal.getRealizedPnl());
            m.setPaperPnl(signal.getRealizedPnl());
            if (signal.getExitPrice() != null) {
                m.setPaperExitPrice(signal.getExitPrice());
            }

            exitTelemetryRepository.findBySignalIdOrderByCreatedAtDesc(signalId).stream()
                    .findFirst()
                    .ifPresent(t -> applyPaperExitTelemetry(m, t, signal));

            if (m.getPaperHoldSeconds() == null) {
                Instant entry = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : signal.getCreatedAt();
                Instant exit = signal.getOutcomeTime() != null ? signal.getOutcomeTime() : Instant.now();
                if (entry != null) {
                    m.setPaperHoldSeconds(Math.max(0, ChronoUnit.SECONDS.between(entry, exit)));
                }
            }
            if (m.getPaperMaxProfit() == null && signal.getMaxFavorableExcursion() != null) {
                m.setPaperMaxProfit(signal.getMaxFavorableExcursion());
            }
            if (m.getPaperMaxDrawdown() == null && signal.getMaxAdverseExcursion() != null) {
                m.setPaperMaxDrawdown(signal.getMaxAdverseExcursion().abs());
            }
            if (m.getPaperExitReason() == null && signal.getExpiryReason() != null) {
                m.setPaperExitReason(truncate(signal.getExpiryReason(), 128));
            }

            metricsRepository.save(m);
            attemptFinalize(m);
        });
    }

    @Transactional
    public void onLivePositionClosed(OmsOrder exitOrder, BigDecimal exitPrice, int fillCount) {
        UUID signalId = resolveSignalId(exitOrder);
        if (signalId == null) {
            return;
        }
        metricsRepository.findBySignalIdAndDeletedFalse(signalId).ifPresent(m -> {
            boolean isLive = exitOrder.getExecutionMode() == ExecutionMode.LIVE;
            if (!isLive) {
                return;
            }
            m.setLivePositionClosed(true);
            m.setLiveClosedAt(Instant.now());
            m.setLiveFillCount(Math.max(m.getLiveFillCount(), (long) fillCount));
            m.setLiveExitPrice(exitPrice);
            m.setLiveExitReason(resolveLiveExitReason(exitOrder));

            if (m.getLiveEntryAt() != null) {
                m.setLiveHoldSeconds(Math.max(0, ChronoUnit.SECONDS.between(m.getLiveEntryAt(), m.getLiveClosedAt())));
            }
            if (m.getLiveEntryPrice() != null && exitPrice != null && m.getLiveQuantity() != null) {
                BigDecimal pnl = computeDirectionalPnl(
                        m.getDirection(),
                        m.getLiveEntryPrice(),
                        exitPrice,
                        m.getLiveQuantity());
                m.setLiveRealizedPnl(pnl);
                m.setLivePnl(pnl);
            }

            recomputeFillDrift(m);
            metricsRepository.save(m);
            attemptFinalize(m);
        });
    }

    private void applyFill(
            ExecutionComparisonMetrics m,
            OmsOrder order,
            UUID signalId,
            BigDecimal avgFillPrice,
            int fillCount,
            long latencyMs) {
        boolean isLive = order.getExecutionMode() == ExecutionMode.LIVE;
        boolean isExit = isExitOrder(order);

        if (isExit) {
            if (isLive) {
                onLivePositionClosed(order, avgFillPrice, fillCount);
            }
            return;
        }

        if (isLive) {
            m.setLiveEntryFilled(true);
            m.setLiveFillPrice(avgFillPrice);
            m.setLiveEntryPrice(avgFillPrice);
            m.setLiveLatencyMs(latencyMs);
            m.setLiveFillCount(Math.max(m.getLiveFillCount(), (long) fillCount));
            m.setLiveEntryAt(Instant.now());
            if (order.getQuantity() != null) {
                m.setLiveQuantity(order.getQuantity());
            }
        } else {
            m.setPaperEntryFilled(true);
            m.setPaperFillPrice(avgFillPrice);
            m.setPaperEntryPrice(avgFillPrice);
            m.setPaperLatencyMs(latencyMs);
            m.setPaperFillCount(Math.max(m.getPaperFillCount(), (long) fillCount));
            m.setPaperEntryAt(Instant.now());
            if (order.getQuantity() != null) {
                m.setPaperQuantity(order.getQuantity());
            }
        }

        if (m.getDirection() == null && order.getSide() != null) {
            m.setDirection(order.getSide().toUpperCase());
        }
        if (m.getLiveQuantity() != null && m.getPaperQuantity() != null) {
            m.setQuantityDrift(m.getLiveQuantity().subtract(m.getPaperQuantity()).abs());
        }

        computeEntrySlippage(m);
        recomputeFillDrift(m);

        if (m.getReconciliationStatus() == null || STATUS_PENDING.equals(m.getReconciliationStatus())) {
            m.setReconciliationStatus(STATUS_AWAITING_CLOSE);
        }
        metricsRepository.save(m);
        executionComparisonServiceComputeDivergence(m);
        log.debug("reconciliation.entry_filled signalId={} live={} fillCount={}", signalId, isLive, fillCount);
    }

    private void applyPaperExitTelemetry(ExecutionComparisonMetrics m, StrategyExitTelemetry t, StrategySignalEntity signal) {
        m.setPaperExitCategory(t.getExitCategory());
        m.setPaperHoldSeconds(t.getHoldSeconds());
        m.setPaperExitReason(truncate(t.getExitReason(), 128));
        if (t.getUnrealizedPnlPeak() != null) {
            m.setPaperMaxProfit(t.getUnrealizedPnlPeak());
        }
        if (t.getUnrealizedPnlTrough() != null) {
            m.setPaperMaxDrawdown(t.getUnrealizedPnlTrough().abs());
        }
        if (m.getPaperRealizedPnl() == null && signal.getRealizedPnl() != null) {
            m.setPaperRealizedPnl(signal.getRealizedPnl());
        }
    }

    private void attemptFinalize(ExecutionComparisonMetrics m) {
        if (!m.isPaperPositionClosed()) {
            m.setReconciliationStatus(STATUS_AWAITING_CLOSE);
            metricsRepository.save(m);
            return;
        }

        boolean hasLiveLeg = m.getLiveOrderId() != null;
        if (!hasLiveLeg) {
            finalizePaperOnly(m);
            return;
        }

        if (!m.isLivePositionClosed()) {
            m.setReconciliationStatus(STATUS_AWAITING_CLOSE);
            metricsRepository.save(m);
            return;
        }

        computeLifecycleDrifts(m);
        detectQuantityMismatch(m);

        if (m.getReconciliationFailureReason() != null) {
            m.setReconciliationStatus(STATUS_FAILED);
            emitFailureTelemetry(m, m.getReconciliationFailureReason());
        } else {
            m.setReconciliationStatus(STATUS_RECONCILED);
            m.setReconciledAt(Instant.now());
            log.info(
                    "reconciliation.complete signalId={} strategy={} pnlDrift={} holdDriftSec={} slippagePct={}",
                    m.getSignalId(),
                    m.getStrategyKey(),
                    m.getPnlDrift(),
                    m.getHoldTimeDrift(),
                    m.getSlippageDivergencePct());
        }
        metricsRepository.save(m);
    }

    private void finalizePaperOnly(ExecutionComparisonMetrics m) {
        if (m.getPaperRealizedPnl() != null) {
            m.setPaperPnl(m.getPaperRealizedPnl());
        }
        m.setReconciliationStatus(STATUS_PAPER_ONLY);
        m.setReconciledAt(Instant.now());
        metricsRepository.save(m);
        log.info("reconciliation.paper_only signalId={} strategy={}", m.getSignalId(), m.getStrategyKey());
    }

    private void computeLifecycleDrifts(ExecutionComparisonMetrics m) {
        if (m.getPaperRealizedPnl() != null && m.getLiveRealizedPnl() != null) {
            BigDecimal drift = m.getLiveRealizedPnl().subtract(m.getPaperRealizedPnl());
            m.setPnlDrift(drift);
            m.setPnlDivergence(drift);
        }
        if (m.getPaperHoldSeconds() != null && m.getLiveHoldSeconds() != null) {
            long holdDrift = m.getLiveHoldSeconds() - m.getPaperHoldSeconds();
            m.setHoldTimeDrift(holdDrift);
            m.setHoldTimeDiffSeconds(holdDrift);
        }
        recomputeFillDrift(m);
        computeEntrySlippage(m);
        executionComparisonServiceComputeDivergence(m);
    }

    private void recomputeFillDrift(ExecutionComparisonMetrics m) {
        long paperFills = m.getPaperFillCount();
        long liveFills = m.getLiveFillCount();
        m.setFillCountDifference(Math.abs(liveFills - paperFills));
        long paperPartials = Math.max(0, paperFills - 1);
        long livePartials = Math.max(0, liveFills - 1);
        m.setPartialFillDifference(Math.abs(livePartials - paperPartials));
    }

    private void computeEntrySlippage(ExecutionComparisonMetrics m) {
        if (m.getLiveEntryPrice() == null || m.getPaperEntryPrice() == null) {
            return;
        }
        if (m.getPaperEntryPrice().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        BigDecimal entrySlip = m.getLiveEntryPrice().subtract(m.getPaperEntryPrice())
                .divide(m.getPaperEntryPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        m.setSlippageEntry(entrySlip);
        if (m.getLiveFillPrice() != null && m.getPaperFillPrice() != null) {
            if (m.getPaperFillPrice().compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal divergence = m.getLiveFillPrice().subtract(m.getPaperFillPrice())
                        .divide(m.getPaperFillPrice(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                m.setSlippageDivergencePct(divergence);
            }
        }
        if (m.getLiveExitPrice() != null && m.getPaperExitPrice() != null
                && m.getPaperExitPrice().compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal exitSlip = m.getLiveExitPrice().subtract(m.getPaperExitPrice())
                    .divide(m.getPaperExitPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            m.setSlippageExit(exitSlip);
        }
    }

    private void detectQuantityMismatch(ExecutionComparisonMetrics m) {
        if (m.getLiveQuantity() == null || m.getPaperQuantity() == null) {
            return;
        }
        BigDecimal drift = m.getLiveQuantity().subtract(m.getPaperQuantity()).abs();
        BigDecimal tolerance = qtyTolerance != null ? qtyTolerance : new BigDecimal("0.001");
        if (drift.compareTo(tolerance) > 0) {
            String reason = "Quantity mismatch paper=" + m.getPaperQuantity().toPlainString()
                    + " live=" + m.getLiveQuantity().toPlainString();
            m.setReconciliationFailureReason(reason);
            m.setQuantityDrift(drift);
        }
    }

    private void executionComparisonServiceComputeDivergence(ExecutionComparisonMetrics m) {
        if (m.getLiveFillPrice() == null || m.getPaperFillPrice() == null) {
            return;
        }
        if (m.getPaperFillPrice().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        BigDecimal divergence = m.getLiveFillPrice().subtract(m.getPaperFillPrice())
                .divide(m.getPaperFillPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        m.setSlippageDivergencePct(divergence);
    }

    private void emitFailureTelemetry(ExecutionComparisonMetrics m, String reason) {
        log.error(
                "reconciliation.failed signalId={} strategy={} symbol={} reason={}",
                m.getSignalId(),
                m.getStrategyKey(),
                m.getSymbol(),
                reason);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("signalId", m.getSignalId() != null ? m.getSignalId().toString() : "");
        payload.put("strategyKey", m.getStrategyKey() != null ? m.getStrategyKey() : "");
        payload.put("symbol", m.getSymbol() != null ? m.getSymbol() : "");
        payload.put("reason", reason);
        payload.put("severity", "ERROR");
        eventPublisher.publishEvent(new OperationalRealtimeEvent("reconciliation_failure", payload));
    }

    public static UUID resolveSignalId(OmsOrder order) {
        if (order.getSignalId() != null) {
            return order.getSignalId();
        }
        String key = order.getIdempotencyKey();
        if (key != null && key.startsWith("outcome-exit:")) {
            String[] parts = key.split(":");
            if (parts.length >= 2) {
                try {
                    return UUID.fromString(parts[1]);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static boolean isExitOrder(OmsOrder order) {
        String key = order.getIdempotencyKey();
        return key != null && key.startsWith("outcome-exit:");
    }

    private static String resolveLiveExitReason(OmsOrder order) {
        String key = order.getIdempotencyKey();
        if (key != null && key.startsWith("outcome-exit:")) {
            String[] parts = key.split(":");
            if (parts.length >= 4) {
                return truncate(parts[3], 128);
            }
        }
        return "BROKER_EXIT";
    }

    private static BigDecimal computeDirectionalPnl(String direction, BigDecimal entry, BigDecimal exit, BigDecimal qty) {
        if (entry == null || exit == null || qty == null) {
            return null;
        }
        BigDecimal raw = "SELL".equalsIgnoreCase(direction)
                ? entry.subtract(exit).multiply(qty)
                : exit.subtract(entry).multiply(qty);
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public int countFillLegs(OmsOrder order) {
        return tradeRepository.findByOrder_IdOrderByCreatedAtAsc(order.getId()).size();
    }
}
