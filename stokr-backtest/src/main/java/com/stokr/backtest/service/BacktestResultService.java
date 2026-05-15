package com.stokr.backtest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.backtest.domain.BacktestEquityCurvePoint;
import com.stokr.backtest.domain.BacktestMetrics;
import com.stokr.backtest.domain.BacktestResult;
import com.stokr.backtest.domain.BacktestRun;
import com.stokr.backtest.domain.BacktestTrade;
import com.stokr.backtest.metrics.MetricsCalculator;
import com.stokr.backtest.repository.BacktestEquityCurveRepository;
import com.stokr.backtest.repository.BacktestMetricsRepository;
import com.stokr.backtest.repository.BacktestResultRepository;
import com.stokr.backtest.repository.BacktestTradeRepository;
import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.repository.OmsExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BacktestResultService {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final OmsExecutionRepository executionRepository;
    private final BacktestTradeRepository tradeRepository;
    private final BacktestEquityCurveRepository equityCurveRepository;
    private final BacktestMetricsRepository metricsRepository;
    private final BacktestResultRepository resultRepository;
    private final EquityCurveService equityCurveService;
    private final ReplayValidationService replayValidationService;
    private final MetricsCalculator metricsCalculator;
    private final ObjectMapper objectMapper;

    @Transactional
    public BacktestReplayOutcome persistForRun(BacktestRun run, ReplayLoopTelemetry loopTelemetry) {
        tradeRepository.softDeleteForRun(run.getId());
        equityCurveRepository.softDeleteForRun(run.getId());
        List<OmsExecution> executions = executionRepository.findAllForBacktestRunOrdered(run.getId());
        List<TradeFact> facts = buildFacts(executions, run);

        tradeRepository.saveAll(facts.stream().map(TradeFact::trade).toList());

        List<BigDecimal> pnls = facts.stream().map(TradeFact::pnl).toList();
        List<EquityCurveService.EquityPoint> curve = equityCurveService.buildFromTradePnls(pnls);
        List<BacktestEquityCurvePoint> curveRows = new ArrayList<>(curve.size());
        for (int i = 0; i < curve.size(); i++) {
            EquityCurveService.EquityPoint p = curve.get(i);
            TradeFact fact = facts.get(i);
            Instant pt = fact.trade().getClosedAt() != null ? fact.trade().getClosedAt()
                    : (fact.trade().getOpenedAt() != null ? fact.trade().getOpenedAt()
                    : (run.getRangeEnd() != null ? run.getRangeEnd() : Instant.EPOCH));
            BacktestEquityCurvePoint row = new BacktestEquityCurvePoint();
            row.setRun(run);
            row.setPointTime(pt);
            row.setCumulativePnl(p.cumulativePnl().setScale(8, RoundingMode.HALF_UP));
            row.setDrawdown(p.drawdown().setScale(8, RoundingMode.HALF_UP));
            curveRows.add(row);
        }
        equityCurveRepository.saveAll(curveRows);

        BigDecimal totalPnl = pnls.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(8, RoundingMode.HALF_UP);
        long wins = pnls.stream().filter(p -> p.compareTo(BigDecimal.ZERO) > 0).count();
        BigDecimal winRate = pnls.isEmpty() ? BigDecimal.ZERO :
                BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(pnls.size()), MC);
        BigDecimal grossProfit = pnls.stream().filter(p -> p.compareTo(BigDecimal.ZERO) > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLoss = pnls.stream().filter(p -> p.compareTo(BigDecimal.ZERO) < 0).map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profitFactor = grossLoss.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : grossProfit.divide(grossLoss, MC);
        BigDecimal maxDrawdown = metricsCalculator.maxDrawdown(curve.stream().map(EquityCurveService.EquityPoint::cumulativePnl).toList());
        BigDecimal expectancy = pnls.isEmpty() ? BigDecimal.ZERO : totalPnl.divide(BigDecimal.valueOf(pnls.size()), MC);
        long avgHoldingSeconds = (long) facts.stream().mapToLong(TradeFact::holdingSeconds).average().orElse(0);
        BigDecimal sharpe = metricsCalculator.sharpe(pnls, BigDecimal.ZERO);

        ReplayValidationService.ReplayValidationReport report = replayValidationService.validateRun(run.getId());

        BacktestMetrics metrics = metricsRepository.findByRun_IdAndDeletedFalse(run.getId()).orElseGet(BacktestMetrics::new);
        metrics.setRun(run);
        metrics.setTotalTrades(pnls.size());
        metrics.setTotalPnl(totalPnl);
        metrics.setWinRate(winRate);
        metrics.setProfitFactor(profitFactor);
        metrics.setSharpeRatio(sharpe);
        metrics.setMaxDrawdown(maxDrawdown);
        metrics.setAvgRr(BigDecimal.ZERO);
        metrics.setExpectancy(expectancy);
        metrics.setAvgHoldingTimeSeconds(avgHoldingSeconds);
        metrics.setReplayHash(report.replayHash());
        metrics.setMetricsJson("{\"deterministic\":" + report.deterministic() + "}");
        metricsRepository.save(metrics);

        BacktestResult result = resultRepository.findByRun_IdAndDeletedFalse(run.getId()).orElseGet(BacktestResult::new);
        result.setRun(run);
        result.setPnl(totalPnl);
        result.setProfitFactor(profitFactor);
        result.setWinRate(winRate);
        result.setSharpeRatio(sharpe);
        result.setMaxDrawdown(maxDrawdown);
        result.setRoi(metricsCalculator.roi(totalPnl, startingCapital(run)));
        resultRepository.save(result);

        List<BacktestTrade> persistedTrades = tradeRepository.findByRun_IdAndDeletedFalseOrderByCreatedAtAsc(run.getId());
        List<BacktestEquityCurvePoint> persistedCurve = equityCurveRepository.findByRun_IdAndDeletedFalseOrderByPointTimeAsc(run.getId());
        BacktestMetrics persistedMetrics = metricsRepository.findByRun_IdAndDeletedFalse(run.getId()).orElseThrow();

        return toOutcome(run, report, persistedMetrics, persistedTrades, persistedCurve, true, loopTelemetry);
    }

    @Transactional(readOnly = true)
    public BacktestReplayOutcome loadMaterializedOutcome(BacktestRun run) {
        ReplayValidationService.ReplayValidationReport report = replayValidationService.validateRun(run.getId());
        Optional<BacktestMetrics> mOpt = metricsRepository.findByRun_IdAndDeletedFalse(run.getId());
        if (mOpt.isEmpty()) {
            return partialOutcome(run, report, null);
        }
        List<BacktestTrade> persistedTrades = tradeRepository.findByRun_IdAndDeletedFalseOrderByCreatedAtAsc(run.getId());
        List<BacktestEquityCurvePoint> persistedCurve =
                equityCurveRepository.findByRun_IdAndDeletedFalseOrderByPointTimeAsc(run.getId());
        return toOutcome(run, report, mOpt.get(), persistedTrades, persistedCurve, true, null);
    }

    private BacktestReplayOutcome partialOutcome(BacktestRun run, ReplayValidationService.ReplayValidationReport report, ReplayLoopTelemetry loopTelemetry) {
        return toOutcome(run, report, null, List.of(), List.of(), false, loopTelemetry);
    }

    private BacktestReplayOutcome toOutcome(
            BacktestRun run,
            ReplayValidationService.ReplayValidationReport report,
            BacktestMetrics persistedMetrics,
            List<BacktestTrade> persistedTrades,
            List<BacktestEquityCurvePoint> persistedCurve,
            boolean materialized,
            ReplayLoopTelemetry loopTelemetry
    ) {
        BacktestReplayOutcome.MetricsSnapshot snap = persistedMetrics == null
                ? BacktestReplayOutcome.MetricsSnapshot.empty()
                : new BacktestReplayOutcome.MetricsSnapshot(
                        persistedMetrics.getWinRate(),
                        persistedMetrics.getTotalTrades() != null ? persistedMetrics.getTotalTrades() : 0,
                        persistedMetrics.getProfitFactor(),
                        persistedMetrics.getSharpeRatio(),
                        persistedMetrics.getMaxDrawdown(),
                        persistedMetrics.getAvgRr(),
                        persistedMetrics.getExpectancy(),
                        persistedMetrics.getTotalPnl(),
                        persistedMetrics.getAvgHoldingTimeSeconds()
                );
        return new BacktestReplayOutcome(
                run.getId(),
                run.getStatus().name(),
                run.getStrategyKey(),
                run.getSymbol(),
                run.getRangeStart(),
                run.getRangeEnd(),
                run.getTimeframe(),
                run.getExecutionProfile(),
                materialized,
                report,
                snap,
                persistedTrades.stream().map(t -> new BacktestReplayOutcome.TradeSnapshot(
                        t.getId(),
                        t.getSymbol(),
                        t.getSide(),
                        t.getQuantity(),
                        t.getPrice(),
                        t.getPnl(),
                        t.getOpenedAt(),
                        t.getClosedAt(),
                        t.getHoldingSeconds()
                )).toList(),
                persistedCurve.stream().map(c -> new BacktestReplayOutcome.EquitySnapshot(
                        c.getPointTime(),
                        c.getCumulativePnl(),
                        c.getDrawdown()
                )).toList(),
                loopTelemetry
        );
    }

    private BigDecimal startingCapital(BacktestRun run) {
        String json = run.getExecutionRequestJson();
        if (json == null || json.isBlank()) {
            return BigDecimal.valueOf(100_000);
        }
        try {
            JsonNode cap = objectMapper.readTree(json).get("capital");
            if (cap == null || cap.isNull() || cap.isMissingNode()) {
                return BigDecimal.valueOf(100_000);
            }
            return cap.decimalValue();
        } catch (Exception e) {
            return BigDecimal.valueOf(100_000);
        }
    }

    private static List<TradeFact> buildFacts(List<OmsExecution> executions, BacktestRun run) {
        List<TradeFact> out = new ArrayList<>();
        for (OmsExecution e : executions) {
            BacktestTrade t = new BacktestTrade();
            t.setRun(run);
            t.setSymbol(e.getOrder().getSymbol());
            t.setQuantity(e.getFilledQty());
            t.setPrice(e.getAvgPrice());
            t.setSide(e.getOrder().getSide());
            t.setOpenedAt(e.getExecutionTimestamp());
            t.setClosedAt(e.getExecutionTimestamp());
            t.setHoldingSeconds(0L);
            BigDecimal pnl = "SELL".equalsIgnoreCase(e.getOrder().getSide())
                    ? e.getAvgPrice().multiply(e.getFilledQty())
                    : e.getAvgPrice().multiply(e.getFilledQty()).negate();
            t.setPnl(pnl);
            out.add(new TradeFact(t, pnl, Duration.between(t.getOpenedAt(), t.getClosedAt()).toSeconds()));
        }
        return out;
    }

    private record TradeFact(BacktestTrade trade, BigDecimal pnl, long holdingSeconds) {
    }
}
