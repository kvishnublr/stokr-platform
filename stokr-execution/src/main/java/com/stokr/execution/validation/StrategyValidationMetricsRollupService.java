package com.stokr.execution.validation;

import com.stokr.execution.comparison.ExecutionComparisonMetrics;
import com.stokr.execution.comparison.ExecutionComparisonMetricsRepository;
import com.stokr.execution.sizing.PositionSizingTelemetryRepository;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.lifecycle.StrategyExitTelemetry;
import com.stokr.strategy.lifecycle.StrategyExitTelemetryRepository;
import com.stokr.strategy.operational.StrategyRuntimeHealth;
import com.stokr.strategy.operational.StrategyRuntimeHealthRepository;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.validation.StrategyValidationMetrics;
import com.stokr.strategy.validation.StrategyValidationMetricsRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyValidationMetricsRollupService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final StrategyValidationMetricsRepository metricsRepository;
    private final StrategyDefinitionRepository definitionRepository;
    private final StrategySignalRepository signalRepository;
    private final StrategyExitTelemetryRepository exitTelemetryRepository;
    private final StrategyRuntimeHealthRepository runtimeHealthRepository;
    private final ExecutionComparisonMetricsRepository comparisonRepository;
    private final PositionSizingTelemetryRepository sizingTelemetryRepository;
    private final EntityManager entityManager;

    @Transactional
    public void rollupSession(LocalDate sessionDate) {
        Instant start = ZonedDateTime.of(sessionDate, java.time.LocalTime.of(9, 15), IST).toInstant();
        Instant end = ZonedDateTime.of(sessionDate, java.time.LocalTime.of(15, 35), IST).toInstant();

        for (StrategyDefinition def : definitionRepository.findAllByDeletedFalse(PageRequest.of(0, 500)).getContent()) {
            try {
                rollupStrategy(def.getStrategyKey(), def.getValidationStatus(), sessionDate, start, end);
            } catch (Exception ex) {
                log.error("validation.rollup.failed strategy={} date={} err={}",
                        def.getStrategyKey(), sessionDate, ex.getMessage(), ex);
            }
        }
        log.info("validation.rollup.complete date={}", sessionDate);
    }

    private void rollupStrategy(
            String strategyKey,
            String validationStatus,
            LocalDate sessionDate,
            Instant start,
            Instant end) {
        List<StrategySignalEntity> signals = signalRepository.findAll().stream()
                .filter(s -> !s.isDeleted())
                .filter(s -> s.getBacktestRunId() == null)
                .filter(s -> !Boolean.TRUE.equals(s.getTestTrade()))
                .filter(s -> strategyKey.equalsIgnoreCase(s.getStrategyName()))
                .filter(s -> s.getCreatedAt() != null && !s.getCreatedAt().isBefore(start) && s.getCreatedAt().isBefore(end))
                .toList();

        List<ExecutionComparisonMetrics> comparisons = comparisonRepository
                .findReconciledSince(strategyKey, start).stream()
                .filter(m -> m.getCreatedAt() != null && !m.getCreatedAt().isBefore(start) && m.getCreatedAt().isBefore(end))
                .toList();

        StrategyValidationMetrics row = metricsRepository.findByStrategyNameAndSessionDate(strategyKey, sessionDate)
                .orElseGet(StrategyValidationMetrics::new);
        row.setStrategyName(strategyKey);
        row.setSessionDate(sessionDate);
        row.setValidationStatus(validationStatus != null ? validationStatus : "DRY_RUN");

        row.setSignalsGenerated(signals.size());
        row.setTargetsHit(countOutcome(signals, "TARGET_HIT"));
        row.setStopLossesHit(countOutcome(signals, "STOPLOSS_HIT") + countOutcome(signals, "SL_HIT"));
        row.setPressureExits(countOutcome(signals, "PRESSURE_EXIT") + countOutcome(signals, "BREAKEVEN_EXIT"));
        row.setSampleSize(signals.stream().filter(s -> s.getOutcomeStatus() != null).count());

        List<StrategyExitTelemetry> exits = exitTelemetryRepository.findAll().stream()
                .filter(e -> strategyKey.equalsIgnoreCase(e.getStrategyName()))
                .filter(e -> e.getExitTime() != null && !e.getExitTime().isBefore(start) && e.getExitTime().isBefore(end))
                .toList();

        if (!exits.isEmpty()) {
            double avgHoldSec = exits.stream().mapToLong(StrategyExitTelemetry::getHoldSeconds).average().orElse(0);
            row.setAvgHoldMinutes(BigDecimal.valueOf(avgHoldSec / 60.0).setScale(4, RoundingMode.HALF_UP));
        }

        BigDecimal paperPnl = sumPaperPnl(comparisons, signals);
        BigDecimal livePnl = sumLivePnl(comparisons);
        row.setPaperPnl(paperPnl);
        row.setLivePnl(livePnl);
        if (paperPnl != null && livePnl != null && paperPnl.signum() != 0) {
            row.setPaperLiveDrift(livePnl.subtract(paperPnl)
                    .divide(paperPnl.abs(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)));
        }

        row.setWinRate(computeWinRate(signals));
        row.setPaperWinRate(computeWinRate(signals));
        row.setLiveWinRate(computeComparisonWinRate(comparisons, true));
        if (row.getWinRate() != null && row.getLiveWinRate() != null) {
            row.setWinRateDelta(row.getLiveWinRate().subtract(row.getWinRate()));
        }

        row.setExpectancy(computeExpectancy(signals));
        row.setAvgRMultiple(computeAvgR(signals));
        row.setMaxDrawdown(computeMaxDrawdown(signals));

        row.setFills(countFills(strategyKey, start, end));
        row.setStaleSignalRejections(countStaleRejections(strategyKey, start, end));
        row.setSizingRejections(countSizingRejections(strategyKey, start, end));
        row.setOmsRejectRate(computeOmsRejectRate(strategyKey, start, end));

        long scans = runtimeHealthRepository.findBySessionDateOrderByStrategyNameAsc(sessionDate).stream()
                .filter(h -> strategyKey.equalsIgnoreCase(h.getStrategyName()))
                .mapToLong(StrategyRuntimeHealth::getScansAttempted)
                .sum();
        long integrityBlocks = runtimeHealthRepository.findBySessionDateOrderByStrategyNameAsc(sessionDate).stream()
                .filter(h -> strategyKey.equalsIgnoreCase(h.getStrategyName()))
                .mapToLong(StrategyRuntimeHealth::getScansBlockedIntegrity)
                .sum();
        if (scans > 0) {
            row.setIntegrityRejectionPct(BigDecimal.valueOf(integrityBlocks)
                    .divide(BigDecimal.valueOf(scans), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)));
        }

        applyDriftAnalytics(row, comparisons);
        if (row.getStrategyDegradationScore() == null) {
            row.setStrategyDegradationScore(computeDegradationScore(row));
        }
        row.setUnreconciledTrades(comparisonRepository.countByStrategyKeyAndReconciliationStatusAndDeletedFalse(
                strategyKey, "AWAITING_CLOSE")
                + comparisonRepository.countByStrategyKeyAndReconciliationStatusAndDeletedFalse(strategyKey, "PENDING"));
        row.setReconciliationFailures(comparisonRepository.countByStrategyKeyAndReconciliationStatusAndDeletedFalse(
                strategyKey, "FAILED"));

        metricsRepository.save(row);
    }

    private void applyDriftAnalytics(StrategyValidationMetrics row, List<ExecutionComparisonMetrics> comparisons) {
        if (comparisons.isEmpty()) {
            return;
        }
        List<BigDecimal> slippages = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        List<Long> exitDrifts = new ArrayList<>();
        List<BigDecimal> paperPnls = new ArrayList<>();
        List<BigDecimal> livePnls = new ArrayList<>();

        for (ExecutionComparisonMetrics m : comparisons) {
            if (m.getSlippageDivergencePct() != null) {
                slippages.add(m.getSlippageDivergencePct().abs());
            }
            if (m.getLiveLatencyMs() != null) {
                latencies.add(m.getLiveLatencyMs());
            }
            if (m.getHoldTimeDrift() != null) {
                exitDrifts.add(Math.abs(m.getHoldTimeDrift()));
            }
            if (m.getPaperRealizedPnl() != null) {
                paperPnls.add(m.getPaperRealizedPnl());
            }
            if (m.getLiveRealizedPnl() != null) {
                livePnls.add(m.getLiveRealizedPnl());
            }
        }

        row.setAvgSlippageBps(average(slippages));
        row.setSlippageP50Bps(percentile(slippages, 50));
        row.setSlippageP95Bps(percentile(slippages, 95));
        row.setAvgExecutionLatencyMs(averageLong(latencies));
        row.setLatencyP50Ms(percentileLong(latencies, 50));
        row.setLatencyP95Ms(percentileLong(latencies, 95));
        row.setExitTimingDriftSeconds(averageLong(exitDrifts) != null ? averageLong(exitDrifts) : null);

        BigDecimal paperExp = computeExpectancyFromPnls(paperPnls);
        BigDecimal liveExp = computeExpectancyFromPnls(livePnls);
        if (paperExp != null && liveExp != null) {
            row.setExpectancyDrift(liveExp.subtract(paperExp));
        }
        if (paperExp != null && paperExp.signum() != 0 && liveExp != null) {
            row.setLiveUnderperformancePct(paperExp.subtract(liveExp)
                    .divide(paperExp.abs(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)));
        }
        row.setStrategyDegradationScore(computeDegradationScore(row));
    }

    private BigDecimal computeDegradationScore(StrategyValidationMetrics row) {
        double score = 0;
        if (row.getPaperLiveDrift() != null) {
            score += Math.min(40, row.getPaperLiveDrift().abs().doubleValue());
        }
        if (row.getAvgSlippageBps() != null) {
            score += Math.min(25, row.getAvgSlippageBps().doubleValue());
        }
        if (row.getOmsRejectRate() != null) {
            score += Math.min(20, row.getOmsRejectRate().doubleValue());
        }
        if (row.getIntegrityRejectionPct() != null) {
            score += Math.min(15, row.getIntegrityRejectionPct().doubleValue());
        }
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    private long countOutcome(List<StrategySignalEntity> signals, String status) {
        return signals.stream().filter(s -> status.equalsIgnoreCase(s.getOutcomeStatus())).count();
    }

    private BigDecimal sumPaperPnl(List<ExecutionComparisonMetrics> comparisons, List<StrategySignalEntity> signals) {
        BigDecimal fromComparison = comparisons.stream()
                .map(ExecutionComparisonMetrics::getPaperRealizedPnl)
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (fromComparison.signum() != 0) {
            return fromComparison;
        }
        return signals.stream()
                .map(StrategySignalEntity::getRealizedPnl)
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumLivePnl(List<ExecutionComparisonMetrics> comparisons) {
        return comparisons.stream()
                .map(ExecutionComparisonMetrics::getLiveRealizedPnl)
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal computeWinRate(List<StrategySignalEntity> signals) {
        List<StrategySignalEntity> closed = signals.stream()
                .filter(s -> s.getRealizedPnl() != null)
                .toList();
        if (closed.isEmpty()) {
            return null;
        }
        long wins = closed.stream().filter(s -> s.getRealizedPnl().signum() > 0).count();
        return BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(closed.size()), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal computeComparisonWinRate(List<ExecutionComparisonMetrics> comparisons, boolean live) {
        List<BigDecimal> pnls = comparisons.stream()
                .map(live ? ExecutionComparisonMetrics::getLiveRealizedPnl : ExecutionComparisonMetrics::getPaperRealizedPnl)
                .filter(p -> p != null)
                .toList();
        if (pnls.isEmpty()) {
            return null;
        }
        long wins = pnls.stream().filter(p -> p.signum() > 0).count();
        return BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(pnls.size()), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal computeExpectancy(List<StrategySignalEntity> signals) {
        List<BigDecimal> pnls = signals.stream()
                .map(StrategySignalEntity::getRealizedPnl)
                .filter(p -> p != null)
                .toList();
        return computeExpectancyFromPnls(pnls);
    }

    private BigDecimal computeExpectancyFromPnls(List<BigDecimal> pnls) {
        if (pnls.isEmpty()) {
            return null;
        }
        BigDecimal sum = pnls.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(pnls.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal computeAvgR(List<StrategySignalEntity> signals) {
        List<BigDecimal> rs = new ArrayList<>();
        for (StrategySignalEntity s : signals) {
            if (s.getRealizedPnl() == null || s.getEntryReferencePrice() == null || s.getStopPrice() == null) {
                continue;
            }
            BigDecimal risk = s.getEntryReferencePrice().subtract(s.getStopPrice()).abs();
            if (risk.signum() <= 0) {
                continue;
            }
            BigDecimal qty = s.getSuggestedQty() != null ? s.getSuggestedQty() : BigDecimal.ONE;
            BigDecimal riskAmount = risk.multiply(qty);
            if (riskAmount.signum() <= 0) {
                continue;
            }
            rs.add(s.getRealizedPnl().divide(riskAmount, 6, RoundingMode.HALF_UP));
        }
        if (rs.isEmpty()) {
            return null;
        }
        return rs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(rs.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal computeMaxDrawdown(List<StrategySignalEntity> signals) {
        return signals.stream()
                .map(StrategySignalEntity::getMaxAdverseExcursion)
                .filter(v -> v != null)
                .map(BigDecimal::abs)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private long countFills(String strategyKey, Instant start, Instant end) {
        Object r = entityManager.createNativeQuery("""
                select count(*) from oms_trades t
                join oms_orders o on o.id = t.order_id
                where o.deleted = false and t.deleted = false
                  and o.backtest_run_id is null and o.is_test_trade = false
                  and upper(o.strategy_key) = upper(:sk)
                  and t.created_at >= :start and t.created_at < :end
                """)
                .setParameter("sk", strategyKey)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult();
        return r instanceof Number n ? n.longValue() : 0L;
    }

    private long countStaleRejections(String strategyKey, Instant start, Instant end) {
        Object r = entityManager.createNativeQuery("""
                select count(*) from oms_orders
                where deleted = false and backtest_run_id is null
                  and upper(strategy_key) = upper(:sk)
                  and state = 'REJECTED'
                  and reject_reason ilike '%stale%'
                  and created_at >= :start and created_at < :end
                """)
                .setParameter("sk", strategyKey)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult();
        return r instanceof Number n ? n.longValue() : 0L;
    }

    private long countSizingRejections(String strategyKey, Instant start, Instant end) {
        return sizingTelemetryRepository.findAll().stream()
                .filter(t -> strategyKey.equalsIgnoreCase(t.getStrategyName()))
                .filter(t -> t.isRejected())
                .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(start) && t.getCreatedAt().isBefore(end))
                .count();
    }

    private BigDecimal computeOmsRejectRate(String strategyKey, Instant start, Instant end) {
        Object totalObj = entityManager.createNativeQuery("""
                select count(*) from oms_orders
                where deleted = false and backtest_run_id is null and is_test_trade = false
                  and upper(strategy_key) = upper(:sk)
                  and created_at >= :start and created_at < :end
                """)
                .setParameter("sk", strategyKey)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult();
        long total = totalObj instanceof Number n ? n.longValue() : 0L;
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        Object rejObj = entityManager.createNativeQuery("""
                select count(*) from oms_orders
                where deleted = false and backtest_run_id is null and is_test_trade = false
                  and upper(strategy_key) = upper(:sk)
                  and state = 'REJECTED'
                  and created_at >= :start and created_at < :end
                """)
                .setParameter("sk", strategyKey)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult();
        long rejected = rejObj instanceof Number n ? n.longValue() : 0L;
        return BigDecimal.valueOf(rejected)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private static Long averageLong(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        OptionalDouble avg = values.stream().mapToLong(Long::longValue).average();
        return avg.isPresent() ? Math.round(avg.getAsDouble()) : null;
    }

    private static BigDecimal percentile(List<BigDecimal> values, int pct) {
        if (values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(pct / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    private static Long percentileLong(List<Long> values, int pct) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(pct / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
