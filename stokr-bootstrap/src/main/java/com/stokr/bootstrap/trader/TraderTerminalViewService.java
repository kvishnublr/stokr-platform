package com.stokr.bootstrap.trader;

import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.repository.BacktestJobRepository;
import com.stokr.marketdata.cache.LatestPriceCache;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.execution.broker.BrokerPositionTruthSyncState;
import com.stokr.execution.guard.ExecutionGuardTelemetryService;
import com.stokr.execution.guard.ExecutionQualityScoringService;
import com.stokr.execution.guard.ExecutionTimelineService;
import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.dto.OmsExecutionRowDto;
import com.stokr.oms.dto.OmsOrderSummaryDto;
import com.stokr.oms.dto.PortfolioOverviewDto;
import com.stokr.oms.dto.PortfolioExposureDto;
import com.stokr.oms.query.OmsReadParams;
import com.stokr.oms.query.PipelineMode;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.oms.service.OmsQueryService;
import com.stokr.oms.service.OmsReconciliationService;
import com.stokr.oms.service.PortfolioQueryService;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import com.stokr.user.dto.TraderExecutionModePreferenceDto;
import com.stokr.user.service.TraderExecutionModePreferenceService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Trader-execution-plane facade over central market intelligence and OMS — UI must call these paths,
 * not {@code /api/marketdata} or {@code /api/market} directly.
 */
@Service
@Slf4j
public class TraderTerminalViewService {

    private static final int CHART_CAP = 500;
    private static final List<String> DEFAULT_WATCH_SYMBOLS = List.of(
            "NIFTY_FUT", "BANKNIFTY_FUT", "NIFTY", "RELIANCE", "BERGEPAINT"
    );

    private static final Duration LIVE_TICK_MAX_AGE = Duration.ofSeconds(120);

    private final MarketDataQueryService marketDataQueryService;
    private final LatestPriceCache latestPriceCache;
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
    private final StrategyUniverseResolverService strategyUniverseResolverService;
    private final BrokerPositionTruthService brokerPositionTruthService;

    public TraderTerminalViewService(
            MarketDataQueryService marketDataQueryService,
            LatestPriceCache latestPriceCache,
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
            ExecutionQualityScoringService executionQualityScoringService,
            StrategyUniverseResolverService strategyUniverseResolverService,
            BrokerPositionTruthService brokerPositionTruthService
    ) {
        this.marketDataQueryService = marketDataQueryService;
        this.latestPriceCache = latestPriceCache;
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
        this.strategyUniverseResolverService = strategyUniverseResolverService;
        this.brokerPositionTruthService = brokerPositionTruthService;
    }

    public List<Map<String, Object>> marketWatchProjection() {
        return marketWatchProjection(null);
    }

    public List<Map<String, Object>> marketWatchProjection(UUID userId) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        strategyUniverseResolverService.resolveActiveBindings().stream()
                .flatMap(b -> strategyUniverseResolverService.resolveSymbolsForGroup(b.getUniverseGroup().getId()).stream())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(symbols::add);
        if (userId != null) {
            for (StrategyInstance si : strategyInstanceRepository.findAllForUserWithDefinition(userId)) {
                if (si.isEnabled() && !si.isDeleted() && si.getSymbol() != null && !si.getSymbol().isBlank()) {
                    symbols.add(si.getSymbol().trim());
                }
            }
            for (StrategySignalEntity sig : strategySignalRepository.findRecentForTrader(userId, PageRequest.of(0, 80))) {
                if (sig.getSymbol() != null && !sig.getSymbol().isBlank()) {
                    symbols.add(sig.getSymbol().trim());
                }
            }
        }
        if (symbols.isEmpty()) {
            symbols.addAll(DEFAULT_WATCH_SYMBOLS);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String symbol : symbols) {
            Map<String, Object> row = watchRowForSymbol(symbol);
            if (row != null) {
                out.add(row);
            }
        }
        if (out.isEmpty()) {
            for (String symbol : DEFAULT_WATCH_SYMBOLS) {
                Map<String, Object> row = watchRowForSymbol(symbol);
                if (row != null) {
                    out.add(row);
                }
            }
        }
        return out;
    }

    private Map<String, Object> watchRowForSymbol(String symbol) {
        BigDecimal live = lastPrice(symbol);
        if (live == null || live.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, "5m", 2);
        if (bars.isEmpty()) {
            bars = marketDataQueryService.lastBarsAsc(symbol, "1m", 2);
        }
        double lastClose = live.doubleValue();
        double prevClose = lastClose;
        Instant lastOpen = null;
        if (!bars.isEmpty()) {
            MarketdataCandle last = bars.get(bars.size() - 1);
            MarketdataCandle prev = bars.size() > 1 ? bars.get(bars.size() - 2) : null;
            prevClose = prev != null ? bd(prev.getClosePrice()) : lastClose;
            lastOpen = last.getOpenTime();
        }
        double pct = prevClose == 0d ? 0d : ((lastClose - prevClose) / prevClose) * 100d;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("price", String.format("%.2f", lastClose));
        row.put("ltp", live);
        row.put("changePct", String.format("%.2f", pct));
        row.put("lastOpenTime", lastOpen != null ? lastOpen.toString() : null);
        return row;
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

        // Batch-collect unique symbols for LTP lookup (avoid N+1)
        Map<String, BigDecimal> ltpCache = new HashMap<>();
        for (StrategySignalEntity s : rows) {
            if (s.getSymbol() != null && !ltpCache.containsKey(s.getSymbol())) {
                ltpCache.put(s.getSymbol(), null); // placeholder
            }
        }
        for (String sym : ltpCache.keySet()) {
            try {
                ltpCache.put(sym, lastPrice(sym));
            } catch (Exception ex) {
                ltpCache.put(sym, BigDecimal.ZERO);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (StrategySignalEntity s : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId().toString());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            m.put("symbol", s.getSymbol());
            m.put("signalType", s.getSignalType() != null ? s.getSignalType().name() : null);
            m.put("strategyKey", s.getStrategyName());
            m.put("strategyName", s.getStrategyName());
            m.put("reason", s.getReason());
            m.put("suggestedQty", s.getSuggestedQty() != null ? s.getSuggestedQty().toPlainString() : null);
            m.put("confidenceScore", s.getConfidenceScore() != null ? s.getConfidenceScore().toPlainString() : null);
            m.put("entryReferencePrice", s.getEntryReferencePrice());
            m.put("stopPrice", s.getStopPrice());
            m.put("targetPrice", s.getTargetPrice());
            BigDecimal riskReward = computeRiskReward(s.getEntryReferencePrice(), s.getStopPrice(), s.getTargetPrice());
            m.put("riskReward", riskReward);
            ConfirmationRank.Result confirmation = ConfirmationRank.rank(s.getConfidenceScore(), riskReward, null);
            m.put("confirmationScore", confirmation.score());
            m.put("confirmationTier", confirmation.tier());
            m.put("highConviction", confirmation.highConviction());
            m.put("executionMode", s.getPipeline() != null ? s.getPipeline() : null);
            m.put("pipeline", s.getPipeline());

            // --- Enriched fields: outcome, PnL, LTP ---
            m.put("outcomeStatus", s.getOutcomeStatus());
            m.put("exitPrice", s.getExitPrice());
            m.put("realizedPnl", s.getRealizedPnl());
            m.put("unrealizedPnl", s.getUnrealizedPnl());

            BigDecimal ltp = ltpCache.getOrDefault(s.getSymbol(), BigDecimal.ZERO);
            m.put("ltp", ltp);

            // Compute live P&L: (ltp - entry) * qty for BUY, (entry - ltp) * qty for SELL
            BigDecimal livePnl = null;
            if (ltp != null && ltp.compareTo(BigDecimal.ZERO) > 0
                    && s.getEntryReferencePrice() != null
                    && s.getEntryReferencePrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal qty = s.getSuggestedQty() != null ? s.getSuggestedQty() : BigDecimal.ONE;
                boolean isBuy = s.getSignalType() != null && "BUY".equalsIgnoreCase(s.getSignalType().name());
                if (isBuy) {
                    livePnl = ltp.subtract(s.getEntryReferencePrice()).multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP);
                } else {
                    livePnl = s.getEntryReferencePrice().subtract(ltp).multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP);
                }
            }
            // Use realizedPnl if signal is closed, otherwise use livePnl
            if (s.getRealizedPnl() != null && s.getOutcomeStatus() != null
                    && ("TARGET_HIT".equalsIgnoreCase(s.getOutcomeStatus())
                        || "SL_HIT".equalsIgnoreCase(s.getOutcomeStatus())
                        || "PRESSURE_EXIT".equalsIgnoreCase(s.getOutcomeStatus())
                        || "CLOSED".equalsIgnoreCase(s.getOutcomeStatus()))) {
                m.put("pnl", s.getRealizedPnl());
            } else {
                m.put("pnl", livePnl);
            }

            out.add(m);
        }
        return out;
    }

    private static BigDecimal computeRiskReward(BigDecimal entry, BigDecimal stop, BigDecimal target) {
        if (entry == null || stop == null || target == null) {
            return null;
        }
        BigDecimal risk = entry.subtract(stop).abs();
        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return target.subtract(entry).abs().divide(risk, 4, java.math.RoundingMode.HALF_UP);
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
        BrokerPositionTruthSnapshot brokerTruth = safeBrokerTruth(userId);
        Map<String, BrokerPositionTruthSnapshot.BrokerTruthPositionRow> truthBySymbol = new LinkedHashMap<>();
        for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow row : brokerTruth.positions()) {
            truthBySymbol.put(row.symbol(), row);
        }

        PortfolioOverviewDto overview = portfolioQueryService.overview(userId);
        PortfolioExposureDto exposure = safeExposure(userId);
        var recon = omsReconciliationService.reconcileUser(userId);
        var broker = zerodhaBrokerOperationsService.status(userId);
        TraderExecutionModePreferenceDto mode = traderExecutionModePreferenceService.get(userId);
        List<StrategyInstance> strategyInstances = strategyInstanceRepository.findAllForUserWithDefinition(userId);
        List<PortfolioPosition> positions = portfolioPositionRepository.findByUserIdAndDeletedFalse(userId);
        List<OmsExecution> executions = omsExecutionRepository.findAllForUserOrdered(userId);
        OmsReadParams liveParams = new OmsReadParams(
                null, null, null, null, null, null, null, PipelineMode.LIVE
        );
        List<OmsOrderSummaryDto> orders = omsQueryService.pageOrders(userId, liveParams, PageRequest.of(0, 50)).getContent();
        Map<String, BigDecimal> stopBySymbol = slTargetBySymbol(userId, true);
        Map<String, BigDecimal> targetBySymbol = slTargetBySymbol(userId, false);

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
        BigDecimal sumRealized = BigDecimal.ZERO;
        BigDecimal sumUnrealized = BigDecimal.ZERO;
        for (PortfolioPosition p : positions) {
            if (p.isSimulation()) {
                continue;
            }
            BigDecimal qty = p.getQuantity() == null ? BigDecimal.ZERO : p.getQuantity();
            String symbol = p.getSymbol();
            BigDecimal ltp = lastPrice(symbol);
            BigDecimal avg = p.getAvgPrice() == null ? BigDecimal.ZERO : p.getAvgPrice();
            BrokerPositionTruthSnapshot.BrokerTruthPositionRow truth = truthBySymbol.get(
                    BrokerPositionTruthService.normalizeSymbol(symbol));
            BigDecimal unreal = resolveUnrealizedPnl(qty, avg, ltp, p.getUnrealizedPnl(), truth);
            BigDecimal realized = p.getRealizedPnl() == null ? BigDecimal.ZERO : p.getRealizedPnl();
            BigDecimal mtm = realized.add(unreal);
            sumRealized = sumRealized.add(realized);
            if (qty.compareTo(BigDecimal.ZERO) != 0) {
                sumUnrealized = sumUnrealized.add(unreal);
            }
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
            row.put("exposureNotional", exposureNotional);
            row.put("quantitySource", truth != null
                    && truth.brokerQty() != null
                    && truth.brokerQty().compareTo(BigDecimal.ZERO) != 0
                    ? "BROKER"
                    : "OMS");
            if (truth != null) {
                row.put("brokerQty", truth.brokerQty());
                row.put("brokerSyncState", truth.rowSyncState());
                row.put("brokerUnrealizedPnl", truth.brokerUnrealizedPnl());
            } else {
                row.put("brokerSyncState", brokerTruth.syncState().name());
            }
            row.put("strategySource", strategyInstances.stream()
                    .filter(si -> symbol.equalsIgnoreCase(si.getSymbol()))
                    .map(si -> si.getDefinition() != null ? si.getDefinition().getStrategyKey() : null)
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .toList());
            row.put("currentSignalState", latestSignalState(symbol, userId));
            row.put("stopLoss", stopBySymbol.get(BrokerPositionTruthService.normalizeSymbol(symbol)));
            row.put("targetPrice", targetBySymbol.get(BrokerPositionTruthService.normalizeSymbol(symbol)));
            row.put("trailingStop", null);
            if (qty.compareTo(BigDecimal.ZERO) == 0) {
                row.put("exitReason", "FLAT");
                row.put("holdDurationSeconds", null);
                closedPositions.add(row);
            } else {
                openPositions.add(row);
            }
        }

        OmsReadParams liveParamsDup = liveParams;
        List<OmsExecutionRowDto> execRows = omsQueryService.pageExecutions(userId, liveParamsDup, PageRequest.of(0, 100)).getContent();
        List<Map<String, Object>> unifiedOrders = buildUnifiedOrders(userId, orders);

        boolean brokerPnlSource = brokerTruth.brokerConnected() && brokerTruth.lastSyncAt() != null;
        if (brokerPnlSource) {
            applyBrokerFirstWorkstation(
                    brokerTruth,
                    openPositions,
                    closedPositions,
                    broker,
                    mode,
                    strategyInstances,
                    userId,
                    lastFillAtBySymbol,
                    netExecQtyBySymbol,
                    stopBySymbol,
                    targetBySymbol
            );
        } else {
            // Zerodha mirror requires a connected session — never show stale OMS portfolio ghosts.
            openPositions.clear();
            closedPositions.clear();
        }

        BigDecimal totalRealized = sumRealized.setScale(8, java.math.RoundingMode.HALF_UP);
        BigDecimal totalUnrealized = sumUnrealized.setScale(8, java.math.RoundingMode.HALF_UP);
        int openCount = openPositions.size();
        String pnlSource = "OMS";
        if (brokerPnlSource) {
            openCount = openPositions.size();
            if (openCount > 0) {
                BrokerPnlTotals brokerOpenTotals = summarizeFromOpenPositionRows(openPositions);
                totalRealized = brokerOpenTotals.realized();
                totalUnrealized = brokerOpenTotals.unrealized();
                pnlSource = "BROKER";
            } else {
                // Broker session is flat — hide stale OMS portfolio ghosts from header metrics.
                totalRealized = BigDecimal.ZERO;
                totalUnrealized = BigDecimal.ZERO;
                openCount = 0;
                pnlSource = "BROKER";
            }
        } else {
            totalRealized = BigDecimal.ZERO;
            totalUnrealized = BigDecimal.ZERO;
            openCount = 0;
            pnlSource = "BROKER_REQUIRED";
        }
        BigDecimal totalPnl = totalRealized.add(totalUnrealized).setScale(8, java.math.RoundingMode.HALF_UP);

        List<String> riskWarnings = new ArrayList<>(recon.warnings());
        String riskParityState;
        if (brokerTruth.brokerConnected() && brokerTruth.lastSyncAt() != null) {
            riskParityState = brokerTruth.syncState() != null ? brokerTruth.syncState().name() : "PENDING_SYNC";
            if (brokerTruth.syncState() == BrokerPositionTruthSyncState.MISMATCH) {
                riskWarnings.add("BROKER_POSITION_DRIFT");
            }
        } else {
            riskParityState = recon.warnings().isEmpty() ? "SYNCED" : "MISMATCH";
        }
        boolean riskBlockedBadge = !riskWarnings.isEmpty()
                || (brokerTruth.brokerConnected() && brokerTruth.syncState() == BrokerPositionTruthSyncState.MISMATCH);

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> accountSummary = new LinkedHashMap<>();
        accountSummary.put("totalPnl", totalPnl);
        accountSummary.put("mtmPnl", totalPnl);
        accountSummary.put("realizedPnl", totalRealized);
        accountSummary.put("unrealizedPnl", totalUnrealized);
        accountSummary.put("openPositions", openCount);
        accountSummary.put("pnlSource", pnlSource);
        accountSummary.put("positionsSource", brokerPnlSource ? "BROKER" : "BROKER_REQUIRED");
        accountSummary.put("activeStrategies", strategyInstances.stream()
                .filter(si -> "RUNNING".equalsIgnoreCase(si.getRuntimeState())).count());
        accountSummary.put("brokerConnectionState", broker.connected() ? "BROKER_CONNECTED" : "BROKER_DISCONNECTED");
        accountSummary.put("executionMode", mode.executionMode());
        out.put("accountSummary", accountSummary);
        out.put("badges", List.of(
                mode.executionMode(),
                broker.connected() ? "BROKER_CONNECTED" : "BROKER_DISCONNECTED",
                riskBlockedBadge ? "RISK_BLOCKED" : "SYNCED"
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
        Map<String, Object> riskControls = new LinkedHashMap<>();
        riskControls.put("reconciliationWarnings", riskWarnings);
        riskControls.put("parityState", riskParityState);
        riskControls.put("tokenValid", broker.tokenValid());
        riskControls.put("brokerHealth", broker.health());
        riskControls.put("liveEligible", broker.connected() && broker.tokenValid());
        out.put("riskControls", riskControls);
        out.put("latestSignals", strategyFeed(userId));
        out.put("executionGuardEvents", executionGuardTelemetryService.recentGuardEvents(userId, 100));
        out.put("executionQualityMetrics", executionGuardTelemetryService.recentQualityMetrics(userId, 100));
        out.put("executionTimeline", executionTimelineService.recentForUser(userId, 200));
        out.put("executionQualityScore", executionQualityScoringService.scoreForUser(userId));
        out.put("brokerTruth", brokerTruth.toApiMap());
        return out;
    }

    public BrokerPositionTruthSnapshot safeBrokerTruth(UUID userId) {
        try {
            return brokerPositionTruthService.syncUser(userId);
        } catch (Exception ex) {
            log.warn("terminal.workstation.broker_truth_sync_failed user={} {}", userId, ex.toString());
            return brokerPositionTruthService.snapshot(userId);
        }
    }

    private PortfolioExposureDto safeExposure(UUID userId) {
        try {
            return portfolioQueryService.exposure(userId);
        } catch (Exception ex) {
            log.warn("terminal.workstation.exposure_failed user={} {}", userId, ex.toString());
            return new PortfolioExposureDto(List.of(), List.of());
        }
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

    private Map<String, BigDecimal> slTargetBySymbol(UUID userId, boolean stopLoss) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        List<StrategySignalEntity> rows = strategySignalRepository.findRecentForTrader(userId, PageRequest.of(0, 100));
        for (StrategySignalEntity sig : rows) {
            if (sig.getSymbol() == null) {
                continue;
            }
            String status = sig.getOutcomeStatus();
            if (status != null
                    && !"PENDING".equalsIgnoreCase(status)
                    && !"RUNNING".equalsIgnoreCase(status)) {
                continue;
            }
            BigDecimal value = stopLoss ? sig.getStopPrice() : sig.getTargetPrice();
            if (value == null) {
                continue;
            }
            out.putIfAbsent(BrokerPositionTruthService.normalizeSymbol(sig.getSymbol()), value);
        }
        return out;
    }

    private BigDecimal lastPrice(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return BigDecimal.ZERO;
        }
        for (String variant : priceLookupVariants(symbol)) {
            BigDecimal cached = latestPriceCache.getLastPrice(variant);
            if (cached != null && cached.compareTo(BigDecimal.ZERO) > 0) {
                return cached;
            }
        }
        for (String variant : priceLookupVariants(symbol)) {
            List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(variant, "1m", 1);
            if (!bars.isEmpty() && bars.getFirst().getClosePrice() != null) {
                MarketdataCandle bar = bars.getFirst();
                if (bar.getOpenTime() != null
                        && Duration.between(bar.getOpenTime(), Instant.now()).compareTo(LIVE_TICK_MAX_AGE) <= 0) {
                    return bar.getClosePrice();
                }
                if (bars.getFirst().getClosePrice().compareTo(BigDecimal.ZERO) > 0) {
                    return bars.getFirst().getClosePrice();
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private static List<String> priceLookupVariants(String symbol) {
        String raw = symbol.trim();
        String upper = raw.toUpperCase();
        String bare = upper.contains(":") ? upper.substring(upper.indexOf(':') + 1) : upper;
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(upper);
        variants.add(bare);
        if (!upper.contains(":")) {
            variants.add("NSE:" + bare);
        }
        return List.copyOf(variants);
    }

    private static BigDecimal resolveUnrealizedPnl(
            BigDecimal qty,
            BigDecimal avg,
            BigDecimal ltp,
            BigDecimal storedUnrealized,
            BrokerPositionTruthSnapshot.BrokerTruthPositionRow truth
    ) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal brokerUnreal = truth != null ? truth.brokerUnrealizedPnl() : null;
        if (brokerUnreal != null && brokerUnreal.compareTo(BigDecimal.ZERO) != 0) {
            return brokerUnreal;
        }
        if (ltp != null && ltp.compareTo(BigDecimal.ZERO) > 0 && avg != null && avg.compareTo(BigDecimal.ZERO) > 0) {
            return ltp.subtract(avg).multiply(qty).setScale(8, java.math.RoundingMode.HALF_UP);
        }
        if (brokerUnreal != null) {
            return brokerUnreal;
        }
        return storedUnrealized != null ? storedUnrealized : BigDecimal.ZERO;
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

    private record BrokerPnlTotals(
            BigDecimal realized,
            BigDecimal unrealized,
            BigDecimal mtm,
            int openCount
    ) {
    }

    private static BrokerPnlTotals summarizeBrokerTotals(BrokerPositionTruthSnapshot brokerTruth) {
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        int openCount = 0;
        for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow row : brokerTruth.positions()) {
            realized = realized.add(row.brokerRealizedPnl() != null ? row.brokerRealizedPnl() : BigDecimal.ZERO);
            unrealized = unrealized.add(row.brokerUnrealizedPnl() != null ? row.brokerUnrealizedPnl() : BigDecimal.ZERO);
            BigDecimal bq = row.brokerQty() != null ? row.brokerQty() : BigDecimal.ZERO;
            if (bq.compareTo(BigDecimal.ZERO) != 0) {
                openCount++;
            }
        }
        realized = realized.setScale(8, java.math.RoundingMode.HALF_UP);
        unrealized = unrealized.setScale(8, java.math.RoundingMode.HALF_UP);
        return new BrokerPnlTotals(realized, unrealized, realized.add(unrealized), openCount);
    }

    /** Header metrics: only open broker legs (qty != 0), not day-flat rows with residual P&L. */
    private static BrokerPnlTotals summarizeFromOpenPositionRows(List<Map<String, Object>> openPositions) {
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        for (Map<String, Object> row : openPositions) {
            Object r = row.get("realizedPnl");
            Object u = row.get("unrealizedPnl");
            if (r instanceof BigDecimal br) {
                realized = realized.add(br);
            } else if (r instanceof Number nr) {
                realized = realized.add(BigDecimal.valueOf(nr.doubleValue()));
            }
            if (u instanceof BigDecimal bu) {
                unrealized = unrealized.add(bu);
            } else if (u instanceof Number nu) {
                unrealized = unrealized.add(BigDecimal.valueOf(nu.doubleValue()));
            }
        }
        realized = realized.setScale(8, java.math.RoundingMode.HALF_UP);
        unrealized = unrealized.setScale(8, java.math.RoundingMode.HALF_UP);
        return new BrokerPnlTotals(realized, unrealized, realized.add(unrealized), openPositions.size());
    }

    private static BrokerPnlTotals summarizeBrokerOpenTotals(BrokerPositionTruthSnapshot brokerTruth) {
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        int openCount = 0;
        for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow row : brokerTruth.positions()) {
            BigDecimal bq = row.brokerQty() != null ? row.brokerQty() : BigDecimal.ZERO;
            if (bq.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            openCount++;
            realized = realized.add(row.brokerRealizedPnl() != null ? row.brokerRealizedPnl() : BigDecimal.ZERO);
            unrealized = unrealized.add(row.brokerUnrealizedPnl() != null ? row.brokerUnrealizedPnl() : BigDecimal.ZERO);
        }
        realized = realized.setScale(8, java.math.RoundingMode.HALF_UP);
        unrealized = unrealized.setScale(8, java.math.RoundingMode.HALF_UP);
        return new BrokerPnlTotals(realized, unrealized, realized.add(unrealized), openCount);
    }

    private void applyBrokerFirstWorkstation(
            BrokerPositionTruthSnapshot brokerTruth,
            List<Map<String, Object>> openPositions,
            List<Map<String, Object>> closedPositions,
            ZerodhaBrokerOperationsService.BrokerStatusDto broker,
            TraderExecutionModePreferenceDto mode,
            List<StrategyInstance> strategyInstances,
            UUID userId,
            Map<String, Instant> lastFillAtBySymbol,
            Map<String, BigDecimal> netExecQtyBySymbol,
            Map<String, BigDecimal> stopBySymbol,
            Map<String, BigDecimal> targetBySymbol
    ) {
        List<Map<String, Object>> brokerOpen = new ArrayList<>();
        List<Map<String, Object>> brokerClosed = new ArrayList<>();
        Set<String> brokerOpenSymbols = new LinkedHashSet<>();

        for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow truth : brokerTruth.positions()) {
            BigDecimal bq = truth.brokerQty() != null ? truth.brokerQty() : BigDecimal.ZERO;
            if (bq.compareTo(BigDecimal.ZERO) != 0) {
                brokerOpen.add(mapBrokerPositionRow(
                        truth, userId, broker, mode, strategyInstances, lastFillAtBySymbol, netExecQtyBySymbol,
                        stopBySymbol, targetBySymbol));
                brokerOpenSymbols.add(truth.symbol());
            } else if (hasBrokerPnl(truth)) {
                brokerClosed.add(mapBrokerFlatRow(truth, userId, broker, mode));
            }
        }

        openPositions.clear();
        openPositions.addAll(brokerOpen);
        // Live terminal mirrors Zerodha only — OMS-only ghosts stay hidden while broker session is active.

        Set<String> flatBrokerSymbols = brokerTruth.positions().stream()
                .filter(t -> t.brokerQty() == null || t.brokerQty().compareTo(BigDecimal.ZERO) == 0)
                .map(BrokerPositionTruthSnapshot.BrokerTruthPositionRow::symbol)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Map<String, Object>> mergedClosed = new ArrayList<>(brokerClosed);
        for (Map<String, Object> omsClosed : closedPositions) {
            String sym = BrokerPositionTruthService.normalizeSymbol(String.valueOf(omsClosed.get("symbol")));
            if (!flatBrokerSymbols.contains(sym) && !brokerOpenSymbols.contains(sym)) {
                mergedClosed.add(omsClosed);
            }
        }
        closedPositions.clear();
        closedPositions.addAll(mergedClosed);
    }

    private static boolean hasBrokerPnl(BrokerPositionTruthSnapshot.BrokerTruthPositionRow truth) {
        BigDecimal r = truth.brokerRealizedPnl() != null ? truth.brokerRealizedPnl() : BigDecimal.ZERO;
        BigDecimal u = truth.brokerUnrealizedPnl() != null ? truth.brokerUnrealizedPnl() : BigDecimal.ZERO;
        return r.compareTo(BigDecimal.ZERO) != 0 || u.compareTo(BigDecimal.ZERO) != 0;
    }

    private Map<String, Object> mapBrokerPositionRow(
            BrokerPositionTruthSnapshot.BrokerTruthPositionRow truth,
            UUID userId,
            ZerodhaBrokerOperationsService.BrokerStatusDto broker,
            TraderExecutionModePreferenceDto mode,
            List<StrategyInstance> strategyInstances,
            Map<String, Instant> lastFillAtBySymbol,
            Map<String, BigDecimal> netExecQtyBySymbol,
            Map<String, BigDecimal> stopBySymbol,
            Map<String, BigDecimal> targetBySymbol
    ) {
        String symbol = truth.symbol();
        BigDecimal qty = truth.brokerQty() != null ? truth.brokerQty() : BigDecimal.ZERO;
        BigDecimal avg = truth.brokerAvgPrice() != null ? truth.brokerAvgPrice() : BigDecimal.ZERO;
        BigDecimal ltp = lastPrice(symbol);
        BigDecimal realized = truth.brokerRealizedPnl() != null ? truth.brokerRealizedPnl() : BigDecimal.ZERO;
        BigDecimal unreal = resolveUnrealizedPnl(qty, avg, ltp, BigDecimal.ZERO, truth);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("side", qty.compareTo(BigDecimal.ZERO) >= 0 ? "LONG" : "SHORT");
        row.put("qty", qty);
        row.put("avgPrice", avg);
        row.put("ltp", ltp);
        row.put("mtmPnl", realized.add(unreal));
        row.put("realizedPnl", realized);
        row.put("unrealizedPnl", unreal);
        row.put("exposurePct", BigDecimal.ZERO);
        row.put("openTime", lastFillAtBySymbol.get(symbol) != null ? lastFillAtBySymbol.get(symbol).toString() : null);
        row.put("brokerStatus", broker.health());
        row.put("executionMode", mode.executionMode());
        row.put("parityState", parityStateForBrokerRow(symbol, qty, netExecQtyBySymbol));
        row.put("brokerQty", qty);
        row.put("brokerSyncState", truth.rowSyncState());
        row.put("brokerUnrealizedPnl", unreal);
        row.put("product", truth.product());
        row.put("strategySource", strategyKeysForSymbol(strategyInstances, symbol));
        row.put("currentSignalState", latestSignalState(symbol, userId));
        row.put("stopLoss", stopBySymbol.get(BrokerPositionTruthService.normalizeSymbol(symbol)));
        row.put("targetPrice", targetBySymbol.get(BrokerPositionTruthService.normalizeSymbol(symbol)));
        row.put("trailingStop", null);
        row.put("pnlSource", "BROKER");
        row.put("quantitySource", "BROKER");
        BigDecimal absQty = qty.abs();
        BigDecimal exposureNotional = absQty.multiply(ltp.compareTo(BigDecimal.ZERO) > 0 ? ltp : avg);
        row.put("exposureNotional", exposureNotional);
        return row;
    }

    private Map<String, Object> mapBrokerFlatRow(
            BrokerPositionTruthSnapshot.BrokerTruthPositionRow truth,
            UUID userId,
            ZerodhaBrokerOperationsService.BrokerStatusDto broker,
            TraderExecutionModePreferenceDto mode
    ) {
        BigDecimal realized = truth.brokerRealizedPnl() != null ? truth.brokerRealizedPnl() : BigDecimal.ZERO;
        BigDecimal unreal = truth.brokerUnrealizedPnl() != null ? truth.brokerUnrealizedPnl() : BigDecimal.ZERO;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", truth.symbol());
        row.put("side", "FLAT");
        row.put("qty", BigDecimal.ZERO);
        row.put("avgPrice", truth.brokerAvgPrice() != null ? truth.brokerAvgPrice() : BigDecimal.ZERO);
        row.put("ltp", lastPrice(truth.symbol()));
        row.put("mtmPnl", realized.add(unreal));
        row.put("realizedPnl", realized);
        row.put("unrealizedPnl", unreal);
        row.put("brokerStatus", broker.health());
        row.put("executionMode", mode.executionMode());
        row.put("parityState", "FLAT");
        row.put("brokerQty", BigDecimal.ZERO);
        row.put("brokerSyncState", truth.rowSyncState());
        row.put("product", truth.product());
        row.put("exitReason", "BROKER_DAY_FLAT");
        row.put("pnlSource", "BROKER");
        row.put("currentSignalState", latestSignalState(truth.symbol(), userId));
        return row;
    }

    private static String parityStateForBrokerRow(
            String symbol,
            BigDecimal brokerQty,
            Map<String, BigDecimal> netExecQtyBySymbol
    ) {
        BigDecimal execNet = execNetQtyForSymbol(symbol, netExecQtyBySymbol);
        if (execNet.compareTo(brokerQty) == 0) {
            return "SYNCED";
        }
        if (brokerQty.compareTo(BigDecimal.ZERO) != 0 && execNet.abs().compareTo(brokerQty.abs()) < 0) {
            return "PARTIAL_FILL";
        }
        return "MISMATCH";
    }

    private static BigDecimal execNetQtyForSymbol(String symbol, Map<String, BigDecimal> netExecQtyBySymbol) {
        if (netExecQtyBySymbol.containsKey(symbol)) {
            return netExecQtyBySymbol.get(symbol);
        }
        String norm = BrokerPositionTruthService.normalizeSymbol(symbol);
        for (Map.Entry<String, BigDecimal> entry : netExecQtyBySymbol.entrySet()) {
            if (BrokerPositionTruthService.normalizeSymbol(entry.getKey()).equals(norm)) {
                return entry.getValue();
            }
        }
        return BigDecimal.ZERO;
    }

    private static List<String> strategyKeysForSymbol(List<StrategyInstance> strategyInstances, String symbol) {
        return strategyInstances.stream()
                .filter(si -> symbol.equalsIgnoreCase(si.getSymbol())
                        || BrokerPositionTruthService.normalizeSymbol(si.getSymbol()).equals(symbol))
                .map(si -> si.getDefinition() != null ? si.getDefinition().getStrategyKey() : null)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
    }
}
