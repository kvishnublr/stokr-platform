package com.stokr.bootstrap.trader;

import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.repository.BacktestJobRepository;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Trader-execution-plane facade over central market intelligence and OMS — UI must call these paths,
 * not {@code /api/marketdata} or {@code /api/market} directly.
 */
@Service
public class TraderTerminalViewService {

    private static final int CHART_CAP = 500;

    private final MarketDataQueryService marketDataQueryService;
    private final StrategySignalRepository strategySignalRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final BacktestJobRepository backtestJobRepository;
    @Value("${stokr.strategy.symbols:NIFTY_FUT,BANKNIFTY_FUT}")
    private String strategySymbolsCsv;

    public TraderTerminalViewService(
            MarketDataQueryService marketDataQueryService,
            StrategySignalRepository strategySignalRepository,
            OmsOrderRepository omsOrderRepository,
            BacktestJobRepository backtestJobRepository
    ) {
        this.marketDataQueryService = marketDataQueryService;
        this.strategySignalRepository = strategySignalRepository;
        this.omsOrderRepository = omsOrderRepository;
        this.backtestJobRepository = backtestJobRepository;
    }

    public List<Map<String, Object>> marketWatchProjection() {
        List<String> symbols = Arrays.stream(strategySymbolsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String symbol : symbols) {
            List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, "5m", 2);
            if (bars.isEmpty()) {
                continue;
            }
            MarketdataCandle last = bars.get(bars.size() - 1);
            MarketdataCandle prev = bars.size() > 1 ? bars.get(bars.size() - 2) : null;
            double lastClose = bd(last.getClosePrice());
            double prevClose = prev != null ? bd(prev.getClosePrice()) : lastClose;
            double pct = prevClose == 0d ? 0d : ((lastClose - prevClose) / prevClose) * 100d;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", symbol);
            row.put("price", String.format("%.2f", lastClose));
            row.put("changePct", String.format("%.2f", pct));
            row.put("lastOpenTime", last.getOpenTime() != null ? last.getOpenTime().toString() : null);
            out.add(row);
        }
        return out;
    }

    public List<Map<String, Object>> chartSeries(String symbol, String intervalOrTf, int limit) {
        String tf = intervalOrTf != null && !intervalOrTf.isBlank() ? intervalOrTf : "5m";
        int capped = Math.min(Math.max(limit, 1), CHART_CAP);
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, tf, capped);
        return mapCandlesToChartRows(bars);
    }

    public List<MarketdataCandle> replayCandlesRange(String symbol, String timeframe, Instant start, Instant end) {
        return marketDataQueryService.rangeAsc(symbol, timeframe, start, end);
    }

    public List<Map<String, Object>> strategyFeed(UUID userId) {
        List<StrategySignalEntity> rows = strategySignalRepository.findRecentForTrader(userId, PageRequest.of(0, 30));
        List<Map<String, Object>> out = new ArrayList<>();
        for (StrategySignalEntity s : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId().toString());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            m.put("symbol", s.getSymbol());
            m.put("signalType", s.getSignalType() != null ? s.getSignalType().name() : null);
            m.put("strategyName", s.getStrategyName());
            m.put("reason", s.getReason());
            m.put("suggestedQty", s.getSuggestedQty() != null ? s.getSuggestedQty().toPlainString() : null);
            m.put("confidenceScore", s.getConfidenceScore() != null ? s.getConfidenceScore().toPlainString() : null);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> executionSummary(UUID userId) {
        long total = omsOrderRepository.countByUserIdAndDeletedFalse(userId);
        long openLike = omsOrderRepository.countByUserIdAndDeletedFalseAndBacktestRunIdIsNullAndStateIn(
                userId,
                List.of(
                        OrderState.CREATED,
                        OrderState.VALIDATED,
                        OrderState.RISK_CHECK,
                        OrderState.PENDING_SUBMISSION,
                        OrderState.SUBMITTED,
                        OrderState.ACCEPTED,
                        OrderState.PARTIALLY_FILLED
                )
        );
        long rejected = omsOrderRepository.countByUserIdAndDeletedFalseAndState(userId, OrderState.REJECTED);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ordersTotal", total);
        m.put("openPipelineOrdersApprox", openLike);
        m.put("rejectedOrders", rejected);
        m.put("projection", "TRADER_EXECUTION_CLIENT");
        return m;
    }

    public Map<String, Object> replaySummary(UUID userId) {
        Optional<BacktestJob> jobOpt = backtestJobRepository.findFirstByUserIdAndDeletedFalseOrderByUpdatedAtDesc(userId);
        if (jobOpt.isEmpty()) {
            return Map.of("hasRecentJob", false);
        }
        BacktestJob j = jobOpt.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasRecentJob", true);
        m.put("jobId", j.getId().toString());
        m.put("status", j.getStatus().name());
        m.put("replayDiagnosis", j.getReplayDiagnosis());
        m.put("progress", j.getProgress());
        m.put("processedBars", j.getProcessedBars());
        m.put("totalBars", j.getTotalBars());
        m.put("signalsEmitted", j.getReplaySignalsEmitted());
        m.put("executionEvents", j.getReplayExecutionEvents());
        m.put("durationMs", j.getReplayDurationMs());
        m.put("message", j.getMessage());
        m.put("terminal", j.getStatus() == BacktestJobStatus.COMPLETED
                || j.getStatus() == BacktestJobStatus.FAILED
                || j.getStatus() == BacktestJobStatus.CANCELLED);
        m.put("projection", "TRADER_REPLAY_SUMMARY");
        return m;
    }

    private static List<Map<String, Object>> mapCandlesToChartRows(List<MarketdataCandle> bars) {
        List<Map<String, Object>> out = new ArrayList<>(bars.size());
        for (MarketdataCandle c : bars) {
            Map<String, Object> row = new LinkedHashMap<>();
            long t = c.getOpenTime() != null ? c.getOpenTime().toEpochMilli() : 0L;
            row.put("time", t);
            row.put("ts", t);
            row.put("open", bd(c.getOpenPrice()));
            row.put("high", bd(c.getHighPrice()));
            row.put("low", bd(c.getLowPrice()));
            row.put("close", bd(c.getClosePrice()));
            row.put("volume", bd(c.getVolume()));
            out.add(row);
        }
        return out;
    }

    private static double bd(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }
}
