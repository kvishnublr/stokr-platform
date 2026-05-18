package com.stokr.bootstrap.trader;

import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.repository.BacktestJobRepository;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.execution.guard.ExecutionGuardTelemetryService;
import com.stokr.execution.guard.ExecutionQualityScoringService;
import com.stokr.execution.guard.ExecutionTimelineService;
import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.dto.OmsExecutionRowDto;
import com.stokr.oms.dto.OmsOrderSummaryDto;
import com.stokr.oms.dto.PortfolioOverviewDto;
import com.stokr.oms.query.OmsReadParams;
import com.stokr.oms.query.PipelineMode;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.oms.service.OmsQueryService;
import com.stokr.oms.service.OmsReconciliationService;
import com.stokr.oms.service.PortfolioQueryService;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import com.stokr.user.dto.TraderExecutionModePreferenceDto;
import com.stokr.user.service.TraderExecutionModePreferenceService;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final PortfolioQueryService portfolioQueryService;
    private final OmsQueryService omsQueryService;
    private final OmsReconciliationService omsReconciliationService;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final OmsExecutionRepository omsExecutionRepository;
    private final PortfolioPositionRepository portfolioPositionRepository;
    private final ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;
    private final TraderExecutionModePreferenceService traderExecutionModePreferenceService;
    private final ExecutionGuardTelemetryService executionGuardTelemetryService;
    private final ExecutionTimelineService executionTimelineService;
    private final ExecutionQualityScoringService executionQualityScoringService;
    @Value("${stokr.strategy.symbols:NIFTY_FUT,BANKNIFTY_FUT}")
    private String strategySymbolsCsv;

    public TraderTerminalViewService(
            MarketDataQueryService marketDataQueryService,
            StrategySignalRepository strategySignalRepository,
            OmsOrderRepository omsOrderRepository,
            BacktestJobRepository backtestJobRepository,
            PortfolioQueryService portfolioQueryService,
            OmsQueryService omsQueryService,
            OmsReconciliationService omsReconciliationService,
            StrategyInstanceRepository strategyInstanceRepository,
            OmsExecutionRepository omsExecutionRepository,
            PortfolioPositionRepository portfolioPositionRepository,
            ZerodhaBrokerOperationsService zerodhaBrokerOperationsService,
            TraderExecutionModePreferenceService traderExecutionModePreferenceService,
            ExecutionGuardTelemetryService executionGuardTelemetryService,
            ExecutionTimelineService executionTimelineService,
            ExecutionQualityScoringService executionQualityScoringService
    ) {
        this.marketDataQueryService = marketDataQueryService;
        this.strategySignalRepository = strategySignalRepository;
        this.omsOrderRepository = omsOrderRepository;
        this.backtestJobRepository = backtestJobRepository;
        this.portfolioQueryService = portfolioQueryService;
        this.omsQueryService = omsQueryService;
        this.omsReconciliationService = omsReconciliationService;
        this.strategyInstanceRepository = strategyInstanceRepository;
        this.omsExecutionRepository = omsExecutionRepository;
        this.portfolioPositionRepository = portfolioPositionRepository;
        this.zerodhaBrokerOperationsService = zerodhaBrokerOperationsService;
        this.traderExecutionModePreferenceService = traderExecutionModePreferenceService;
        this.executionGuardTelemetryService = executionGuardTelemetryService;
        this.executionTimelineService = executionTimelineService;
        this.executionQualityScoringService = executionQualityScoringService;
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
        return strategyFeed(userId, 30);
    }

    public List<Map<String, Object>> strategyFeed(UUID userId, int limit) {
        int cap = Math.max(1, Math.min(1000, limit));
        List<StrategySignalEntity> rows = strategySignalRepository.findRecentForTrader(userId, PageRequest.of(0, cap));
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

    public Map<String, Object> terminalWorkstation(UUID userId) {
        PortfolioOverviewDto overview = portfolioQueryService.overview(userId);
        var exposure = portfolioQueryService.exposure(userId);
        var recon = omsReconciliationService.reconcileUser(userId);
        var broker = zerodhaBrokerOperationsService.status(userId);
        TraderExecutionModePreferenceDto mode = traderExecutionModePreferenceService.get(userId);
        List<StrategyInstance> strategyInstances = strategyInstanceRepository.findAllForUserWithDefinition(userId);
        List<PortfolioPosition> positions = portfolioPositionRepository.findByUserIdAndDeletedFalse(userId);
        List<OmsExecution> executions = omsExecutionRepository.findAllForUserOrdered(userId);

        Map<String, BigDecimal> netExecQtyBySymbol = new LinkedHashMap<>();
        Map<String, Instant> lastFillAtBySymbol = new LinkedHashMap<>();
        for (OmsExecution e : executions) {
            if (e.getOrder() == null || e.getOrder().getSymbol() == null) {
                continue;
            }
            String symbol = e.getOrder().getSymbol();
            BigDecimal qty = e.getFilledQty() == null ? BigDecimal.ZERO : e.getFilledQty();
            if ("BUY".equalsIgnoreCase(e.getOrder().getSide())) {
                netExecQtyBySymbol.merge(symbol, qty, BigDecimal::add);
            } else {
                netExecQtyBySymbol.merge(symbol, qty.negate(), BigDecimal::add);
            }
            Instant ts = e.getExecutionTimestamp() != null ? e.getExecutionTimestamp() : e.getFillTime();
            if (ts != null) {
                Instant prev = lastFillAtBySymbol.get(symbol);
                if (prev == null || ts.isAfter(prev)) {
                    lastFillAtBySymbol.put(symbol, ts);
                }
            }
        }

        List<Map<String, Object>> openPositions = new ArrayList<>();
        List<Map<String, Object>> closedPositions = new ArrayList<>();
        for (PortfolioPosition p : positions) {
            BigDecimal qty = p.getQuantity() == null ? BigDecimal.ZERO : p.getQuantity();
            String symbol = p.getSymbol();
            BigDecimal ltp = lastPrice(symbol);
            BigDecimal avg = p.getAvgPrice() == null ? BigDecimal.ZERO : p.getAvgPrice();
            BigDecimal unreal = p.getUnrealizedPnl() == null ? BigDecimal.ZERO : p.getUnrealizedPnl();
            BigDecimal realized = p.getRealizedPnl() == null ? BigDecimal.ZERO : p.getRealizedPnl();
            BigDecimal mtm = realized.add(unreal);
            BigDecimal absQty = qty.abs();
            BigDecimal exposureNotional = absQty.multiply(ltp.compareTo(BigDecimal.ZERO) > 0 ? ltp : avg);
            BigDecimal totalExposure = exposure.bySymbol().stream()
                    .map(x -> x.exposureNotional() == null ? BigDecimal.ZERO : x.exposureNotional())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal exposurePct = totalExposure.compareTo(BigDecimal.ZERO) > 0
                    ? exposureNotional.multiply(BigDecimal.valueOf(100)).divide(totalExposure, 4, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            BigDecimal execNet = netExecQtyBySymbol.getOrDefault(symbol, BigDecimal.ZERO);
            String parityState;
            if (execNet.compareTo(qty) == 0) {
                parityState = "SYNCED";
            } else if (qty.compareTo(BigDecimal.ZERO) == 0 && execNet.compareTo(BigDecimal.ZERO) != 0) {
                parityState = "EXIT_PENDING";
            } else if (execNet.abs().compareTo(qty.abs()) < 0) {
                parityState = "PARTIAL_FILL";
            } else {
                parityState = "MISMATCH";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", symbol);
            row.put("side", qty.compareTo(BigDecimal.ZERO) >= 0 ? "LONG" : "SHORT");
            row.put("qty", qty);
            row.put("avgPrice", avg);
            row.put("ltp", ltp);
            row.put("mtmPnl", mtm);
            row.put("realizedPnl", realized);
            row.put("unrealizedPnl", unreal);
            row.put("exposurePct", exposurePct);
            row.put("openTime", lastFillAtBySymbol.get(symbol) != null ? lastFillAtBySymbol.get(symbol).toString() : null);
            row.put("brokerStatus", broker.health());
            row.put("executionMode", mode.executionMode());
            row.put("parityState", parityState);
            row.put("strategySource", strategyInstances.stream()
                    .filter(si -> symbol.equalsIgnoreCase(si.getSymbol()))
                    .map(si -> si.getDefinition() != null ? si.getDefinition().getStrategyKey() : null)
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .toList());
            row.put("currentSignalState", latestSignalState(symbol, userId));
            row.put("stopLoss", null);
            row.put("trailingStop", null);
            if (qty.compareTo(BigDecimal.ZERO) == 0) {
                row.put("exitReason", "FLAT");
                row.put("holdDurationSeconds", null);
                closedPositions.add(row);
            } else {
                openPositions.add(row);
            }
        }

        OmsReadParams liveParams = new OmsReadParams(
                null, null, null, null, null, null, null, PipelineMode.LIVE
        );
        List<OmsOrderSummaryDto> orders = omsQueryService.pageOrders(userId, liveParams, PageRequest.of(0, 50)).getContent();
        List<OmsExecutionRowDto> execRows = omsQueryService.pageExecutions(userId, liveParams, PageRequest.of(0, 100)).getContent();
        List<Map<String, Object>> unifiedOrders = buildUnifiedOrders(userId, orders);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountSummary", Map.of(
                "totalPnl", overview.cumulativePnl(),
                "realizedPnl", overview.realizedPnl(),
                "unrealizedPnl", overview.unrealizedPnl(),
                "openPositions", overview.openPositionCount(),
                "activeStrategies", strategyInstances.stream().filter(si -> "RUNNING".equalsIgnoreCase(si.getRuntimeState())).count(),
                "brokerConnectionState", broker.connected() ? "BROKER_CONNECTED" : "BROKER_DISCONNECTED",
                "executionMode", mode.executionMode()
        ));
        out.put("badges", List.of(
                mode.executionMode(),
                broker.connected() ? "BROKER_CONNECTED" : "BROKER_DISCONNECTED",
                recon.warnings().isEmpty() ? "SYNCED" : "RISK_BLOCKED"
        ));
        out.put("openPositions", openPositions);
        out.put("closedPositions", closedPositions);
        out.put("orders", unifiedOrders);
        out.put("executions", execRows);
        out.put("strategyAllocations", strategyInstances.stream().map(si -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", si.getId().toString());
            m.put("strategyKey", si.getDefinition() != null ? si.getDefinition().getStrategyKey() : null);
            m.put("strategyName", si.getDefinition() != null ? si.getDefinition().getDisplayName() : null);
            m.put("symbol", si.getSymbol());
            m.put("runtimeState", si.getRuntimeState());
            m.put("executionMode", si.getExecutionMode());
            m.put("allocationAmount", si.getAllocationAmount());
            m.put("riskMultiplier", si.getRiskMultiplier());
            m.put("maxDailyLoss", si.getMaxDailyLoss());
            m.put("startedAt", si.getStartedAt() != null ? si.getStartedAt().toString() : null);
            return m;
        }).toList());
        out.put("riskControls", Map.of(
                "reconciliationWarnings", recon.warnings(),
                "parityState", recon.warnings().isEmpty() ? "SYNCED" : "MISMATCH",
                "tokenValid", broker.tokenValid(),
                "brokerHealth", broker.health(),
                "liveEligible", broker.connected() && broker.tokenValid()
        ));
        out.put("latestSignals", strategyFeed(userId));
        out.put("executionGuardEvents", executionGuardTelemetryService.recentGuardEvents(userId, 100));
        out.put("executionQualityMetrics", executionGuardTelemetryService.recentQualityMetrics(userId, 100));
        out.put("executionTimeline", executionTimelineService.recentForUser(userId, 200));
        out.put("executionQualityScore", executionQualityScoringService.scoreForUser(userId));
        return out;
    }

    private List<Map<String, Object>> buildUnifiedOrders(UUID userId, List<OmsOrderSummaryDto> omsOrders) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> existingOmsFingerprints = new HashSet<>();
        for (OmsOrderSummaryDto row : omsOrders) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", row.id() != null ? row.id().toString() : null);
            m.put("createdAt", row.createdAt() != null ? row.createdAt().toString() : null);
            m.put("symbol", row.symbol());
            m.put("side", row.side());
            m.put("state", row.state());
            m.put("executionMode", row.executionMode());
            m.put("strategyKey", row.strategyKey());
            m.put("quantity", row.quantity());
            m.put("rejectReason", row.rejectReason());
            m.put("source", "OMS");
            m.put("brokerOrderId", null);
            m.put("variety", null);
            m.put("parityState", "SYNCED");
            out.add(m);
            existingOmsFingerprints.add(orderFingerprint(row.symbol(), row.side(), row.quantity() == null ? null : row.quantity().toPlainString()));
        }

        try {
            List<ZerodhaBrokerOperationsService.BrokerOpenOrderDto> brokerRows = zerodhaBrokerOperationsService.recentOrders(userId, 200);
            for (ZerodhaBrokerOperationsService.BrokerOpenOrderDto b : brokerRows) {
                if (existingOmsFingerprints.contains(orderFingerprint(b.symbol(), b.side(), b.quantity() == null ? null : String.valueOf(b.quantity())))) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderId", null);
                m.put("createdAt", b.orderTimestamp() != null ? b.orderTimestamp().toString() : null);
                m.put("symbol", b.symbol());
                m.put("side", b.side());
                m.put("state", b.status());
                m.put("executionMode", "LIVE");
                m.put("strategyKey", b.variety() != null ? "BROKER_DIRECT_" + b.variety() : "BROKER_DIRECT");
                m.put("quantity", b.quantity());
                m.put("rejectReason", b.statusMessage());
                m.put("source", "BROKER");
                m.put("brokerOrderId", b.orderId());
                m.put("variety", b.variety());
                m.put("parityState", "PENDING_SYNC");
                out.add(m);
            }
        } catch (Exception ignored) {
            // keep OMS rows even if broker fetch is unavailable
        }

        out.sort(Comparator.comparing(
                m -> Optional.ofNullable((String) m.get("createdAt")).orElse(""),
                Comparator.reverseOrder()
        ));
        return out;
    }

    private static String orderFingerprint(String symbol, String side, String qty) {
        return String.join("|",
                symbol == null ? "" : symbol.trim().toUpperCase(),
                side == null ? "" : side.trim().toUpperCase(),
                qty == null ? "" : qty.trim());
    }

    private String latestSignalState(String symbol, UUID userId) {
        List<StrategySignalEntity> rows = strategySignalRepository.findRecentForTrader(userId, PageRequest.of(0, 20));
        return rows.stream()
                .filter(r -> symbol.equalsIgnoreCase(r.getSymbol()))
                .findFirst()
                .map(r -> "HOLD".equalsIgnoreCase(r.getSignalType().name()) ? "PAUSED" : "GENERATED")
                .orElse("GENERATED");
    }

    private BigDecimal lastPrice(String symbol) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, "1m", 1);
        if (bars.isEmpty() || bars.getFirst().getClosePrice() == null) {
            return BigDecimal.ZERO;
        }
        return bars.getFirst().getClosePrice();
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
