package com.stokr.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.admin.domain.AdminTestSignalRun;
import com.stokr.admin.dto.TestSignalLabDtos.TestSignalCheckResult;
import com.stokr.admin.dto.TestSignalLabDtos.TestSignalExecutionReport;
import com.stokr.admin.dto.TestSignalLabDtos.TestSignalLabRequest;
import com.stokr.admin.dto.TestSignalLabDtos.TestSignalPreflightReport;
import com.stokr.admin.dto.TestSignalLabDtos.TestSignalRunSummaryDto;
import com.stokr.admin.dto.TestSignalLabDtos.TestSignalTimelineEvent;
import com.stokr.admin.repository.AdminTestSignalRunRepository;
import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.oms.trace.ExecutionTimelineProjection;
import com.stokr.oms.trace.ExecutionTraceEvent;
import com.stokr.risk.model.LiveTraderEligibilityResult;
import com.stokr.risk.service.LiveTradingTraderEligibilityService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.execution.broker.LiveBrokerFillSyncService;
import com.stokr.execution.pipeline.OrderIntentProcessor;
import com.stokr.execution.safety.MarketCloseProtectionService;
import com.stokr.execution.safety.OmsSafetyGateService;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.service.SignalPriceEnrichmentService;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.BrokerAccountRepository;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AdminTestSignalLabService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AdminTestSignalRunRepository runRepository;
    private final AuthUserRepository authUserRepository;
    private final BrokerAccountRepository brokerAccountRepository;
    private final PlatformBrokerFeedSessionRepository platformBrokerFeedSessionRepository;
    private final StrategySignalPipelineService strategySignalPipelineService;
    private final OrderIntentProcessor orderIntentProcessor;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final StrategySignalRepository strategySignalRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final PortfolioPositionRepository portfolioPositionRepository;
    private final LiveTradingTraderEligibilityService liveTradingTraderEligibilityService;
    private final ExecutionTimelineProjection executionTimelineProjection;
    private final AdminOperationalSnapshotService operationalSnapshotService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final AdminTestSignalLabSquareOffService squareOffService;
    private final StrategyExecutionConfigService strategyExecutionConfigService;
    private final SignalPriceEnrichmentService signalPriceEnrichmentService;
    private final OmsSafetyGateService omsSafetyGateService;
    private final MarketCloseProtectionService marketCloseProtectionService;
    private final LiveBrokerFillSyncService liveBrokerFillSyncService;
    private final ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;

    private static final String TEST_LAB_PRODUCT_MIS = "MIS";

    @Transactional
    public TestSignalExecutionReport run(UUID requestedBy, TestSignalLabRequest request) {
        TestSignalPreflightReport preflight = preflight(request);
        if (!preflight.canSubmit()) {
            throw new IllegalStateException("Test signal blocked by preflight: " + String.join(" | ", preflight.blockers()));
        }

        AuthUser trader = authUserRepository.findById(request.traderUserId())
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new IllegalArgumentException("Trader not found: " + request.traderUserId()));

        BrokerAccount broker = resolveBroker(request, trader.getId());
        AdminTestSignalRun run = new AdminTestSignalRun();
        run.setRequestedBy(requestedBy);
        run.setTraderUserId(trader.getId());
        run.setBrokerAccountId(broker != null ? broker.getId() : null);
        run.setBrokerVendor(broker != null ? broker.getVendorCode() : "ZERODHA");
        run.setStrategyKey(request.strategyKey().trim());
        run.setStrategyTemplate(request.strategyTemplate());
        String normalizedSymbol = resolveNormalizedSymbol(request);
        run.setSymbol(normalizedSymbol);
        run.setSide(request.side().trim().toUpperCase());
        run.setQuantity(resolveQuantity(request.quantity(), request.forceQuantityOne()));
        run.setProductType(resolveProductType(
                request.productType(),
                resolveDispatchMode(request.executionMode(), request.dryRunOnly(), request.skipActualBrokerExecution()),
                normalizedSymbol));
        run.setOrderType(request.orderType() == null || request.orderType().isBlank() ? "MARKET" : request.orderType().trim().toUpperCase());
        run.setExchange(request.exchange());
        run.setRequestedPrice(request.price());
        run.setTriggerType(request.triggerType() == null ? "INSTANT" : request.triggerType().trim().toUpperCase());
        run.setExecutionMode(resolveDispatchMode(request.executionMode(), request.dryRunOnly(), request.skipActualBrokerExecution()));
        run.setForceQuantityOne(request.forceQuantityOne());
        run.setDryRunOnly(request.dryRunOnly());
        run.setSkipActualBrokerExecution(request.skipActualBrokerExecution());
        run.setSimulateRejection(request.simulateRejection());
        run.setSimulateTimeout(request.simulateTimeout());
        run.setSimulateStaleWebsocket(request.simulateStaleWebsocket());
        run.setSimulateMarginFailure(request.simulateMarginFailure());
        run.setSimulateBrokerDisconnect(request.simulateBrokerDisconnect());
        String effectiveMode = resolveDispatchMode(request.executionMode(), request.dryRunOnly(), request.skipActualBrokerExecution());
        if ("LIVE".equalsIgnoreCase(effectiveMode)) {
            run.setSimulateRejection(false);
            run.setSimulateTimeout(false);
            run.setSimulateStaleWebsocket(false);
            run.setSimulateMarginFailure(false);
            run.setSimulateBrokerDisconnect(false);
        }
        int squareOffMinutes = resolveAutoSquareOffMinutes(request.autoSquareOffMinutes(), effectiveMode);
        run.setAutoSquareOffMinutes(squareOffMinutes);
        if (squareOffMinutes > 0) {
            run.setAutoSquareOffDueAt(Instant.now().plus(Duration.ofMinutes(squareOffMinutes)));
            run.setSquareOffStatus("PENDING");
        } else if ("LIVE".equalsIgnoreCase(effectiveMode)) {
            run.setSquareOffStatus("IMMEDIATE");
        }
        run.setStatus("RUNNING");
        run.setStartedAt(Instant.now());
        run = runRepository.save(run);

        strategyExecutionConfigService.ensureGlobalExecutionConfig(run.getStrategyKey());

        StrategySignalEntity signal = new StrategySignalEntity();
        signal.setSignalType("SELL".equals(run.getSide()) ? SignalType.SELL : SignalType.BUY);
        signal.setStrategyName(run.getStrategyKey());
        signal.setStrategyVersion("TEST-LAB");
        signal.setSymbol(run.getSymbol());
        signal.setUserId(run.getTraderUserId());
        signal.setPipeline("TEST_LAB");
        signal.setSuggestedQty(run.getQuantity());
        signal.setEntryReferencePrice(run.getRequestedPrice());
        signal.setReason("Admin Test Signal Lab");
        signal.setReasonText(buildReasonText(run));
        signal.setCandleTimestamp(Instant.now());
        signal.setTestTrade(true);
        signal.setTestRunId(run.getId());
        signal.setTestScenario(resolveScenario(run));
        signal.setSignalSource(SignalProvenance.LAB);

        signalPriceEnrichmentService.enrichIfMissing(signal, Instant.now());

        String correlationId = "test-lab:" + run.getId();
        Instant start = Instant.now();

        // Persist signal directly ??? bypass RabbitMQ for test lab runs.
        // This eliminates both queue hops and makes execution synchronous:
        //   signal save ??? risk ??? execution ??? broker ??? all within this transaction.
        signal.setOutcomeStatus("RUNNING");
        StrategySignalEntity savedSignal = strategySignalRepository.save(signal);
        run.setSignalId(savedSignal.getId());
        runRepository.save(run);

        SignalPersistedMessage msg = new SignalPersistedMessage(
                savedSignal.getId(),
                run.getTraderUserId(),
                correlationId,
                null,
                run.getExecutionMode()
        );
        try {
            // synchronous=true ??? no execution queue, runs inline in this transaction
            orderIntentProcessor.processSignalIntent(msg, true);
        } catch (Exception ex) {
            log.error("test.lab.sync_execution_error runId={} signal={} reason={}",
                    run.getId(), savedSignal.getId(), ex.getMessage(), ex);
            // Rethrow to prevent transaction rollback-only state
            throw new IllegalStateException("Signal processing failed: " + ex.getMessage(), ex);
        }

        entityManager.flush();
        Optional<OmsOrder> order = reconcileResolvedOrder(
                run,
                resolveOrderForRun(run.getSignalId(), run.getTraderUserId(), run.getExecutionMode()));
        if (order.isEmpty()) {
            order = waitForOrder(
                    run.getSignalId(),
                    run.getTraderUserId(),
                    resolveOrderWaitTimeout(run),
                    run.getExecutionMode()
            );
            order = reconcileResolvedOrder(run, order);
        }
        if (order.isPresent()) {
            order = omsOrderRepository.findById(order.get().getId());
        }

        if ("LIVE".equalsIgnoreCase(run.getExecutionMode()) && order.isPresent()) {
            OmsOrder entry = order.get();
            if (isBrokerAcceptedState(entry.getState().name(), true, entry)) {
                try {
                    squareOffService.squareOffImmediately(run, entry, true);
                    entityManager.flush();
                    order = omsOrderRepository.findById(entry.getId());
                } catch (Exception ex) {
                    log.error("test.lab.squareoff_failed runId={} orderId={} {}", run.getId(), entry.getId(), ex.toString());
                    run.setSquareOffStatus("FAILED");
                    runRepository.save(run);
                }
            }
            UUID entryOrderId = order.map(OmsOrder::getId).orElse(entry.getId());
            liveBrokerFillSyncService.syncTestLabRunOrders(
                    run.getTraderUserId(),
                    entryOrderId,
                    run.getSquareOffOrderId(),
                    Duration.ofSeconds(12)
            );
            entityManager.flush();
            order = omsOrderRepository.findById(entryOrderId);
        }

        Map<String, Object> healthSnapshot = buildHealthSnapshot();
        List<TestSignalCheckResult> checks = buildChecks(run, order, healthSnapshot);
        List<TestSignalTimelineEvent> timeline = buildTimeline(run, order.orElse(null));
        Map<String, Object> diagnostics = buildDiagnostics(checks, run, order.orElse(null), healthSnapshot);
        long latency = Duration.between(start, Instant.now()).toMillis();

        run.setTotalLatencyMs(latency);
        run.setCompletedAt(Instant.now());
        run.setStatus("COMPLETED");
        run.setFinalStatus(overallStatus(checks));
        run.setReportJson(writeJson(Map.of("timeline", timeline, "checks", checks, "health", healthSnapshot)));
        run.setDiagnosticsJson(writeJson(diagnostics));
        runRepository.save(run);

        return reportFrom(run, timeline, checks, healthSnapshot, diagnostics);
    }

    @Transactional(readOnly = true)
    public Page<TestSignalRunSummaryDto> list(Pageable pageable) {
        return runRepository.findAllByDeletedFalseOrderByCreatedAtDesc(pageable)
                .map(r -> new TestSignalRunSummaryDto(
                        r.getId(),
                        r.getCreatedAt(),
                        r.getTraderUserId(),
                        r.getStrategyKey(),
                        r.getSymbol(),
                        r.getSide(),
                        r.getQuantity(),
                        r.getExecutionMode(),
                        r.getStatus(),
                        r.getFinalStatus(),
                        r.getSignalId(),
                        r.getOrderId(),
                        r.getSquareOffStatus()
                ));
    }

    @Transactional(readOnly = true)
    public TestSignalPreflightReport preflight(TestSignalLabRequest request) {
        List<TestSignalCheckResult> checks = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        String effectiveMode = resolveDispatchMode(request.executionMode(), request.dryRunOnly(), request.skipActualBrokerExecution());
        boolean livePath = "LIVE".equalsIgnoreCase(effectiveMode) || "BOTH".equalsIgnoreCase(effectiveMode);

        List<String> missing = new ArrayList<>();
        if (request.traderUserId() == null) missing.add("Trader");
        if (request.strategyKey() == null || request.strategyKey().isBlank()) missing.add("Strategy");
        if (request.symbol() == null || request.symbol().isBlank()) missing.add("Symbol");
        boolean hasRequired = missing.isEmpty();
        checks.add(new TestSignalCheckResult(
                "required_fields",
                "Required Fields",
                hasRequired ? "SUCCESS" : "FAILED",
                hasRequired ? "Required fields present" : "Missing: " + String.join(", ", missing),
                hasRequired ? null : "Provide all mandatory fields before running the test",
                hasRequired ? null : "OPEN_FORM_REQUIRED_FIELDS"
        ));
        if (!hasRequired) {
            blockers.add("Missing required fields: " + String.join(", ", missing));
        }

        AuthUser trader = null;
        if (request.traderUserId() != null) {
            trader = authUserRepository.findById(request.traderUserId()).filter(u -> !u.isDeleted()).orElse(null);
        }
        boolean traderReady = trader != null;
        checks.add(new TestSignalCheckResult(
                "trader_exists",
                "Trader Account",
                traderReady ? "SUCCESS" : "FAILED",
                traderReady ? "Trader found: " + (trader.getDisplayName() == null ? trader.getUsername() : trader.getDisplayName()) : "Trader account not found",
                traderReady ? null : "Select a valid active trader account",
                traderReady ? null : "OPEN_TRADER_HEALTH"
        ));
        if (!traderReady) {
            blockers.add("Trader account is missing or invalid");
        }

        String strategyKey = request.strategyKey() == null ? "" : request.strategyKey().trim();
        boolean strategyReady = !strategyKey.isBlank() && strategyDefinitionRepository.findByStrategyKeyAndDeletedFalse(strategyKey)
                .map(s -> s.isEnabled())
                .orElse(false);
        checks.add(new TestSignalCheckResult(
                "strategy_enabled",
                "Strategy Catalog",
                strategyReady ? "SUCCESS" : "FAILED",
                strategyReady ? "Strategy is enabled in catalog" : "Strategy missing or disabled in catalog",
                strategyReady ? null : "Enable strategy in admin strategy catalog",
                strategyReady ? null : "OPEN_STRATEGY_CATALOG"
        ));
        if (!strategyReady) {
            blockers.add("Strategy is not enabled in catalog");
        }

        String strategySegment = strategyDefinitionRepository.findByStrategyKeyAndDeletedFalse(strategyKey)
                .map(s -> s.getSegment())
                .orElse("NSE");
        String expectedExchange = AdminTestSignalLabSymbol.expectedExchange(strategySegment, strategyKey);
        String normalizedSymbol = hasRequired
                ? AdminTestSignalLabSymbol.normalize(request.symbol(), request.exchange(), strategySegment, strategyKey)
                : "";
        boolean symbolSegmentOk = !hasRequired || AdminTestSignalLabSymbol.exchangeMatchesStrategy(
                normalizedSymbol, strategySegment, strategyKey);
        String rawSymbol = request.symbol() == null ? "" : request.symbol().trim().toUpperCase();
        boolean staleMcxHint = symbolSegmentOk
                && ("NSE".equals(expectedExchange) || "BSE".equals(expectedExchange))
                && request.exchange() != null
                && "MCX".equalsIgnoreCase(request.exchange().trim());
        boolean wrongMcxPrefix = symbolSegmentOk
                && ("NSE".equals(expectedExchange) || "BSE".equals(expectedExchange))
                && rawSymbol.startsWith("MCX:");
        checks.add(new TestSignalCheckResult(
                "symbol_exchange",
                "Symbol / Exchange",
                symbolSegmentOk ? "SUCCESS" : "FAILED",
                symbolSegmentOk
                        ? "Canonical symbol " + normalizedSymbol + " (" + expectedExchange + " segment)"
                        : "Symbol does not match strategy segment " + expectedExchange,
                symbolSegmentOk ? null : "Use " + expectedExchange + ":SYMBOL for this strategy (not MCX)",
                symbolSegmentOk ? null : "OPEN_FORM_REQUIRED_FIELDS"
        ));
        if (!symbolSegmentOk) {
            blockers.add("Symbol exchange does not match strategy segment (" + expectedExchange + ")");
        } else if (staleMcxHint || wrongMcxPrefix) {
            checks.add(new TestSignalCheckResult(
                    "symbol_exchange_hint",
                    "Exchange Hint Corrected",
                    "WARNING",
                    "Will route as " + normalizedSymbol + " ??? clear MCX preset or use Load NSE Cash preset",
                    "MCX exchange hint does not apply to " + strategyKey,
                    "OPEN_FORM_REQUIRED_FIELDS"
            ));
        }

        boolean executionConfigReady = strategyReady
                && strategyExecutionConfigService.getByStrategyKey(strategyKey).isPresent();
        checks.add(new TestSignalCheckResult(
                "execution_config",
                "Execution Config",
                executionConfigReady ? "SUCCESS" : "WARNING",
                executionConfigReady
                        ? "Strategy execution config present"
                        : "No execution config ??? will auto-provision on run",
                executionConfigReady ? null : "Config is created automatically before synchronous execution",
                executionConfigReady ? null : "OPEN_STRATEGY_EXECUTION_CONFIG"
        ));

        BrokerAccount broker = resolveBrokerOptional(request, request.traderUserId());
        boolean brokerRequired = livePath;
        boolean brokerReady = !brokerRequired || broker != null;
        checks.add(new TestSignalCheckResult(
                "broker_resolved",
                "Broker Account",
                brokerReady ? "SUCCESS" : "FAILED",
                brokerReady
                        ? (broker == null ? "Broker not required for this execution mode" : "Broker resolved: " + broker.getVendorCode() + " (" + broker.getStatus() + ")")
                        : "No broker account resolved for LIVE/BOTH mode",
                brokerReady ? null : "Connect trader broker account and ensure it is active",
                brokerReady ? null : "RECONNECT_BROKER"
        ));
        if (!brokerReady) {
            blockers.add("No connected broker account for LIVE/BOTH execution");
        }

        if (livePath && trader != null && !strategyKey.isBlank()) {
            String vendor = broker != null && broker.getVendorCode() != null && !broker.getVendorCode().isBlank()
                    ? broker.getVendorCode()
                    : "ZERODHA";
            LiveTraderEligibilityResult eligibility =
                    liveTradingTraderEligibilityService.evaluateForLiveStrategyActivation(trader.getId(), strategyKey, vendor);
            boolean eligible = eligibility != null && eligibility.allowed();
            String message = eligible
                    ? "LIVE execution eligibility passed"
                    : (eligibility == null ? "Eligibility service returned no result" : eligibility.reasonCode() + " - " + eligibility.message());
            checks.add(new TestSignalCheckResult(
                    "live_eligibility",
                    "Live Execution Gate",
                    eligible ? "SUCCESS" : "FAILED",
                    message,
                    eligible ? null : "Resolve live-trading gate failure before running LIVE test",
                    eligible ? null : "OPEN_BROKER_INFRA"
            ));
            if (!eligible) {
                blockers.add("LIVE gate failed: " + message);
            }

            StrategySignalEntity preview = previewSignal(request, trader.getId(), strategyKey);
            OmsSafetyGateService.OmsSafetyGateResult routing =
                    omsSafetyGateService.evaluatePreOrder(preview, trader.getId(), ExecutionMode.LIVE, Instant.now());
            boolean routesLive = routing.effectiveMode() == ExecutionMode.LIVE;
            String routingMsg = routesLive
                    ? "LIVE routing confirmed ??? order will be sent to broker"
                    : "LIVE will downgrade to " + routing.effectiveMode().name()
                            + (routing.reasons().isEmpty() ? "" : " (" + String.join(", ", routing.reasons()) + ")");
            checks.add(new TestSignalCheckResult(
                    "live_routing_preview",
                    "Live Broker Routing",
                    routesLive ? "SUCCESS" : "FAILED",
                    routingMsg,
                    routesLive ? null : "Resolve routing blockers or use PAPER mode for simulated fill only",
                    routesLive ? null : "OPEN_SAFETY_DIAGNOSTICS"
            ));
            if (!routesLive) {
                blockers.add("LIVE broker routing blocked: " + routingMsg);
            }

            boolean sessionOpen = !marketCloseProtectionService.blocksNewLiveEntries(
                    Instant.now(),
                    normalizedSymbol,
                    strategyKey);
            checks.add(new TestSignalCheckResult(
                    "market_session",
                    "Market Session",
                    sessionOpen ? "SUCCESS" : "WARNING",
                    sessionOpen
                            ? "Within platform trading window for this segment"
                            : "Outside platform trading window ??? Kite may reject LIVE orders",
                    sessionOpen ? null : "Wait for exchange session or verify holiday calendar on Kite",
                    sessionOpen ? null : "OPEN_MARKET_FEED"
            ));
            if (!sessionOpen) {
                blockers.add("Market session closed for segment ??? LIVE orders will not reach Kite");
            }
        } else {
            checks.add(new TestSignalCheckResult(
                    "live_eligibility",
                    "Live Execution Gate",
                    "SUCCESS",
                    livePath ? "Live eligibility deferred until trader/strategy selection is complete" : "Not required for simulated/paper execution",
                    null,
                    null
            ));
        }

        if (broker != null && broker.getVendorCode() != null && !broker.getVendorCode().isBlank()) {
            Optional<PlatformBrokerFeedSession> session = platformBrokerFeedSessionRepository
                    .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(broker.getVendorCode());
            boolean wsOpen = session.map(s -> "OPEN".equalsIgnoreCase(s.getWebsocketState())).orElse(false);
            checks.add(new TestSignalCheckResult(
                    "broker_websocket",
                    "Broker Websocket",
                    wsOpen ? "SUCCESS" : "WARNING",
                    wsOpen ? "Broker websocket is OPEN" : "Broker websocket is not OPEN",
                    wsOpen ? null : "Reconnect broker websocket to validate terminal visibility in real-time",
                    wsOpen ? null : "RECONNECT_BROKER"
            ));
        }

        boolean canSubmit = blockers.isEmpty();
        return new TestSignalPreflightReport(canSubmit, effectiveMode, blockers, checks);
    }

    @Transactional(readOnly = true)
    public TestSignalExecutionReport report(UUID id) {
        AdminTestSignalRun run = runRepository.findById(id)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new IllegalArgumentException("Test run not found: " + id));
        List<TestSignalTimelineEvent> timeline = readTimeline(run);
        List<TestSignalCheckResult> checks = readChecks(run);
        Map<String, Object> health = readHealth(run);
        Map<String, Object> diagnostics = readDiagnostics(run);
        return reportFrom(run, timeline, checks, health, diagnostics);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> telemetry() {
        Number total = (Number) entityManager.createNativeQuery(
                        "select count(*) from admin_test_signal_runs where deleted=false")
                .getSingleResult();
        Number success = (Number) entityManager.createNativeQuery(
                        "select count(*) from admin_test_signal_runs where deleted=false and final_status='SUCCESS'")
                .getSingleResult();
        Number failed = (Number) entityManager.createNativeQuery(
                        "select count(*) from admin_test_signal_runs where deleted=false and final_status='FAILED'")
                .getSingleResult();
        Number p95 = (Number) entityManager.createNativeQuery(
                        "select percentile_cont(0.95) within group (order by total_latency_ms) from admin_test_signal_runs where deleted=false and total_latency_ms is not null")
                .getSingleResult();
        Number avg = (Number) entityManager.createNativeQuery(
                        "select avg(total_latency_ms) from admin_test_signal_runs where deleted=false and total_latency_ms is not null")
                .getSingleResult();
        long totalL = total == null ? 0L : total.longValue();
        long successL = success == null ? 0L : success.longValue();
        double successRate = totalL == 0 ? 0.0 : (successL * 100.0) / totalL;
        return Map.of(
                "totalRuns", totalL,
                "successRuns", successL,
                "failedRuns", failed == null ? 0L : failed.longValue(),
                "successRatePct", successRate,
                "avgLatencyMs", avg == null ? 0.0 : avg.doubleValue(),
                "p95LatencyMs", p95 == null ? 0.0 : p95.doubleValue(),
                "sla", Map.of(
                        "successRateTargetPct", 99.0,
                        "p95LatencyTargetMs", 10000
                )
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> options() {
        List<Map<String, Object>> traders = authUserRepository.findTop15ByDeletedFalseOrderByUpdatedAtDesc()
                .stream()
                .map(u -> mapOf(
                        "userId", u.getId().toString(),
                        "username", u.getUsername(),
                        "displayName", u.getDisplayName() == null ? "" : u.getDisplayName(),
                        "liveTradingApproved", u.isLiveTradingApproved()
                ))
                .toList();

        List<Map<String, Object>> brokers = brokerAccountRepository.findAll().stream()
                .filter(a -> !a.isDeleted())
                .limit(200)
                .map(a -> mapOf(
                        "id", a.getId().toString(),
                        "userId", a.getUserId().toString(),
                        "vendorCode", a.getVendorCode(),
                        "status", a.getStatus()
                ))
                .toList();

        List<Map<String, Object>> strategies = strategyDefinitionRepository.findAll().stream()
                .filter(s -> !s.isDeleted() && s.isEnabled())
                .limit(200)
                .map(s -> mapOf(
                        "id", s.getId().toString(),
                        "strategyKey", s.getStrategyKey(),
                        "displayName", s.getDisplayName() == null ? s.getStrategyKey() : s.getDisplayName(),
                        "segment", s.getSegment() == null ? "NSE" : s.getSegment(),
                        "defaultExchange", s.getDefaultExchange() == null ? "NSE" : s.getDefaultExchange()
                ))
                .toList();

        return Map.of(
                "traders", traders,
                "brokerAccounts", brokers,
                "strategies", strategies,
                "executionModes", List.of("SIMULATED", "PAPER", "LIVE", "BOTH"),
                "triggerTypes", List.of("INSTANT", "DELAYED", "MARKET_OPEN_SIMULATION")
        );
    }

    private BrokerAccount resolveBroker(TestSignalLabRequest request, UUID traderUserId) {
        if (request.brokerAccountId() != null) {
            return brokerAccountRepository.findById(request.brokerAccountId())
                    .filter(a -> !a.isDeleted())
                    .orElseThrow(() -> new IllegalArgumentException("Broker account not found: " + request.brokerAccountId()));
        }
        return brokerAccountRepository.findFirstByUserIdAndDeletedFalseOrderByUpdatedAtDesc(traderUserId).orElse(null);
    }

    private BrokerAccount resolveBrokerOptional(TestSignalLabRequest request, UUID traderUserId) {
        try {
            if (traderUserId == null) {
                return null;
            }
            return resolveBroker(request, traderUserId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BigDecimal resolveQuantity(BigDecimal requested, boolean forceOne) {
        if (forceOne) {
            return BigDecimal.ONE;
        }
        if (requested == null || requested.signum() <= 0) {
            return BigDecimal.ONE;
        }
        return requested;
    }

    private String resolveNormalizedSymbol(TestSignalLabRequest request) {
        String strategyKey = request.strategyKey() == null ? "" : request.strategyKey().trim();
        String segment = strategyDefinitionRepository.findByStrategyKeyAndDeletedFalse(strategyKey)
                .map(s -> s.getSegment())
                .orElse("NSE");
        return AdminTestSignalLabSymbol.normalize(request.symbol(), request.exchange(), segment, strategyKey);
    }

    private static String resolveDispatchMode(String mode, boolean dryRunOnly, boolean skipActualBrokerExecution) {
        if (dryRunOnly) {
            return "SIMULATED";
        }
        String normalized = mode == null ? "PAPER" : mode.trim().toUpperCase();
        if (skipActualBrokerExecution && "LIVE".equals(normalized)) {
            return "PAPER";
        }
        return normalized;
    }

    private static String resolveScenario(AdminTestSignalRun run) {
        if (run.isSimulateMarginFailure()) return "SIMULATE_MARGIN_FAILURE";
        if (run.isSimulateBrokerDisconnect()) return "SIMULATE_BROKER_DISCONNECT";
        if (run.isSimulateRejection()) return "SIMULATE_REJECTION";
        if (run.isSimulateTimeout()) return "SIMULATE_TIMEOUT";
        if (run.isSimulateStaleWebsocket()) return "SIMULATE_STALE_WEBSOCKET";
        return null;
    }

    private static String buildReasonText(AdminTestSignalRun run) {
        return "TEST_SIGNAL_LAB|mode=" + run.getExecutionMode() + "|scenario=" + (resolveScenario(run) == null ? "NONE" : resolveScenario(run));
    }

    private Optional<OmsOrder> waitForOrder(UUID signalId, UUID traderUserId, Duration timeout, String requestedMode) {
        if (signalId == null) return Optional.empty();
        Instant until = Instant.now().plus(timeout);
        Optional<OmsOrder> order;
        do {
            order = resolveOrderForRun(signalId, traderUserId, requestedMode);
            if (order.isPresent()) return order;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        } while (Instant.now().isBefore(until));
        return Optional.empty();
    }

    private Optional<OmsOrder> resolveOrderForRun(UUID signalId, UUID traderUserId, String requestedMode) {
        List<OmsOrder> candidates = omsOrderRepository.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(signalId);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if ("LIVE".equalsIgnoreCase(requestedMode)) {
            Optional<OmsOrder> live = candidates.stream()
                    .filter(o -> o.getExecutionMode() == ExecutionMode.LIVE)
                    .findFirst();
            if (live.isPresent()) {
                return live;
            }
        }
        if (traderUserId != null) {
            return candidates.stream()
                    .filter(o -> traderUserId.equals(o.getUserId()))
                    .findFirst();
        }
        return Optional.of(candidates.get(0));
    }

    private Optional<OmsOrder> reconcileResolvedOrder(AdminTestSignalRun run, Optional<OmsOrder> current) {
        if (current.isPresent()) {
            run.setOrderId(current.get().getId());
            return current;
        }
        if (run.getSignalId() == null) {
            return Optional.empty();
        }

        // Fallback: resolve by signal only (covers async insert races / user-id mismatches in upstream paths).
        List<OmsOrder> candidates = omsOrderRepository.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(run.getSignalId());
        if (!candidates.isEmpty()) {
            OmsOrder resolved = resolveOrderForRun(run.getSignalId(), run.getTraderUserId(), run.getExecutionMode())
                    .orElse(candidates.get(0));
            run.setOrderId(resolved.getId());
            return Optional.of(resolved);
        }
        return Optional.empty();
    }

    private static Duration resolveOrderWaitTimeout(AdminTestSignalRun run) {
        if (run == null) {
            return Duration.ofSeconds(8);
        }
        if (run.isDryRunOnly() || "SIMULATED".equalsIgnoreCase(run.getExecutionMode())) {
            return Duration.ofSeconds(2);
        }
        if ("LIVE".equalsIgnoreCase(run.getExecutionMode())) {
            return Duration.ofSeconds(3);
        }
        if ("BOTH".equalsIgnoreCase(run.getExecutionMode())) {
            return Duration.ofSeconds(15);
        }
        return Duration.ofMillis(500);
    }

    private static String resolveProductType(String requested, String executionMode, String symbol) {
        if (requested != null && !requested.isBlank()) {
            String normalized = requested.trim().toUpperCase();
            if ("NRML".equals(normalized) || "CNC".equals(normalized)) {
                return normalized;
            }
        }
        if (symbol != null) {
            String upper = symbol.toUpperCase();
            if (upper.startsWith("MCX:") || upper.contains("CRUDEOIL") || upper.contains("NATURALGAS")) {
                return "NRML";
            }
        }
        if ("LIVE".equalsIgnoreCase(executionMode) || "BOTH".equalsIgnoreCase(executionMode)) {
            return TEST_LAB_PRODUCT_MIS;
        }
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase();
        }
        return TEST_LAB_PRODUCT_MIS;
    }

    private static int resolveAutoSquareOffMinutes(Integer requested, String executionMode) {
        if ("LIVE".equalsIgnoreCase(executionMode)) {
            return 0;
        }
        return requested == null ? 0 : Math.max(0, requested);
    }

    private Map<String, Object> buildHealthSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        var ops = operationalSnapshotService.snapshot();
        snapshot.put("apiHealth", "UP");
        snapshot.put("marketFreshness", ops.marketFreshness());
        snapshot.put("system", ops.system());
        snapshot.put("brokerSessions", ops.brokerSessions());
        snapshot.put("oms", ops.oms());
        return snapshot;
    }

    private List<TestSignalTimelineEvent> buildTimeline(AdminTestSignalRun run, OmsOrder order) {
        List<TestSignalTimelineEvent> out = new ArrayList<>();
        out.add(new TestSignalTimelineEvent("Signal Generated", run.getStartedAt(), "Test signal created from admin lab"));
        if (run.getSignalId() != null) {
            out.add(new TestSignalTimelineEvent("Signal Persisted", run.getStartedAt(), "signalId=" + run.getSignalId()));
        }
        if (order != null) {
            List<ExecutionTraceEvent> timeline = executionTimelineProjection.timelineForOrder(order.getId());
            for (ExecutionTraceEvent e : timeline) {
                String detail = e.payload() != null && e.payload().get("reason") != null
                        ? String.valueOf(e.payload().get("reason"))
                        : "";
                Instant at = null;
                if (e.createdAt() != null) {
                    try {
                        at = Instant.parse(e.createdAt());
                    } catch (Exception ignored) {
                        at = null;
                    }
                }
                out.add(new TestSignalTimelineEvent(e.eventType(), at, detail));
            }
        }
        if (run.getCompletedAt() != null) {
            out.add(new TestSignalTimelineEvent("Verification Complete", run.getCompletedAt(), "Final status=" + run.getFinalStatus()));
        }
        return out;
    }

    private List<TestSignalCheckResult> buildChecks(AdminTestSignalRun run, Optional<OmsOrder> order, Map<String, Object> healthSnapshot) {
        List<TestSignalCheckResult> checks = new ArrayList<>();
        checks.add(check("signal_generated", "Signal Generated", run.getSignalId() != null, "Signal persisted", "Signal not persisted", "Check strategy_signal insert path", null));

        boolean expectsOrder = !run.isDryRunOnly() && !"SIMULATED".equalsIgnoreCase(run.getExecutionMode());
        boolean orderCreated = order.isPresent();
        if (!expectsOrder) {
            checks.add(new TestSignalCheckResult(
                    "execution_submitted",
                    "Execution Submitted",
                    "SUCCESS",
                    "Order submission intentionally skipped for dry-run/simulated mode",
                    null,
                    null
            ));
        } else {
            String preOrderReason = findPreOrderBlockReason(run);
            String failMessage = preOrderReason == null || preOrderReason.isBlank()
                    ? "No OMS order created"
                    : "No OMS order created. Gate/eligibility block: " + preOrderReason;
            checks.add(check("execution_submitted", "Execution Submitted", orderCreated, "OMS order created", failMessage, "Inspect OMS listener / queue health", "OPEN_OMS_MONITOR"));
        }

        boolean liveMode = "LIVE".equalsIgnoreCase(run.getExecutionMode());
        boolean orderIsLive = order.map(o -> o.getExecutionMode() == ExecutionMode.LIVE).orElse(false);
        boolean hasKiteOrderId = order.map(o -> o.getBrokerExternalOrderId() != null && !o.getBrokerExternalOrderId().isBlank())
                .orElse(false);
        if (liveMode) {
            String routingMsg = order.isEmpty()
                    ? "No OMS order found for LIVE run"
                    : orderIsLive
                            ? (hasKiteOrderId
                                    ? "LIVE order submitted to Kite (order_id=" + order.get().getBrokerExternalOrderId() + ")"
                                    : "LIVE order created but no Kite order_id ??? broker did not accept")
                            : "LIVE requested but order executed as "
                                    + order.get().getExecutionMode().name()
                                    + " (simulated ??? nothing appears on Kite)";
            checks.add(check(
                    "live_routing",
                    "Live Broker Routing",
                    orderIsLive && hasKiteOrderId,
                    routingMsg,
                    routingMsg,
                    "Verify broker session and exchange is open on Kite",
                    "OPEN_BROKER_INFRA"
            ));
        }

        boolean brokerAccepted = order.map(o -> isBrokerAcceptedState(o.getState().name(), liveMode, o)).orElse(false);
        String brokerFailMsg = "Broker did not accept";
        if (order.isPresent() && order.get().getRejectReason() != null && !order.get().getRejectReason().isBlank()) {
            brokerFailMsg = order.get().getRejectReason();
        } else if (order.isPresent() && ("FAILED".equals(order.get().getState().name()) || "REJECTED".equals(order.get().getState().name()))) {
            brokerFailMsg = "Broker rejected order (no reason recorded)";
        }
        checks.add(check("broker_accepted", "Broker Accepted", !expectsOrder || brokerAccepted, "Broker accepted execution", brokerFailMsg, "Review broker auth/session in Broker Infra", "RECONNECT_BROKER"));

        boolean filled = order.map(o -> isLiveBrokerFill(o, liveMode)).orElse(false);
        checks.add(check(
                "order_filled",
                "Order Filled",
                !expectsOrder || filled,
                liveMode ? "Order filled at Kite" : "Order filled",
                liveMode ? "No Kite fill recorded ??? check Orders tab on Kite" : "Order not filled yet",
                "Inspect execution timeline for pending state",
                "OPEN_EXECUTION_TIMELINE"
        ));

        boolean positionVisible = liveMode
                ? order.map(o -> isLivePositionVerified(o, run)).orElse(false)
                : portfolioPositionRepository.findByUserIdAndSymbolAndDeletedFalse(run.getTraderUserId(), run.getSymbol())
                        .map(PortfolioPosition::getQuantity)
                        .map(q -> q.signum() != 0)
                        .orElse(false);
        checks.add(check(
                "position_visible",
                "Position Visible",
                !expectsOrder || positionVisible,
                liveMode ? "Kite fill confirmed" : "Position visible in terminal",
                liveMode ? "No confirmed Kite position" : "No position entry yet",
                "Open trader positions and verify sync",
                "OPEN_TRADER_POSITIONS"
        ));

        boolean websocketOpen = platformBrokerFeedSessionRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(run.getBrokerVendor() == null ? "ZERODHA" : run.getBrokerVendor())
                .map(s -> "OPEN".equalsIgnoreCase(s.getWebsocketState()))
                .orElse(false);
        checks.add(check("websocket", "Websocket Delivered", websocketOpen, "Broker websocket OPEN", "Broker websocket not open", "Reconnect broker websocket session", "RECONNECT_BROKER"));

        boolean telegramLogged = order.map(o -> hasExecutionAlerts(o.getId())).orElse(false);
        checks.add(optionalCheck(
                "telegram_alert",
                "Telegram Sent",
                !expectsOrder || telegramLogged,
                "Alert log entry found",
                "No alert log entry yet (test-lab records in-app alert)",
                "Check notification providers and execution alerts",
                "OPEN_ALERTS"
        ));

        boolean reconciled = order.map(o -> hasReconciliationEvent(o.getId())).orElse(false);
        checks.add(optionalCheck(
                "reconciliation",
                "Reconciliation Successful",
                !expectsOrder || reconciled,
                "Reconciliation event found",
                "No reconciliation event found",
                "Check reconciliation worker and broker adapter",
                "OPEN_RECONCILIATION"
        ));

        long latency = run.getTotalLatencyMs() == null ? 0L : run.getTotalLatencyMs();
        long latencyTargetMs = liveMode ? 3_000L : 15_000L;
        boolean latencyOk = latency == 0 || latency < latencyTargetMs;
        String latencyMsg = latencyOk
                ? "Latency " + latency + "ms (target <" + latencyTargetMs + "ms)"
                : "Latency " + latency + "ms exceeds " + latencyTargetMs + "ms target";
        checks.add(check("latency", "Latency Acceptable", latencyOk, latencyMsg, latencyMsg, "Inspect broker path and simulation latency-ms", "OPEN_INFRA_HEALTH"));

        boolean squareOffOk = run.getSquareOffStatus() == null
                || "COMPLETED".equalsIgnoreCase(run.getSquareOffStatus())
                || "IMMEDIATE".equalsIgnoreCase(run.getSquareOffStatus())
                || "NO_POSITION".equalsIgnoreCase(run.getSquareOffStatus());
        if (expectsOrder && liveMode) {
            checks.add(check(
                    "square_off",
                    "Position Squared Off",
                    squareOffOk,
                    "Square-off " + (run.getSquareOffStatus() != null ? run.getSquareOffStatus() : "pending"),
                    "Square-off not completed",
                    "Verify MIS exit order at broker",
                    "OPEN_TRADER_POSITIONS"
            ));
        }

        boolean staleState = Boolean.TRUE.equals(readPath(healthSnapshot, "marketFreshness", "status", "STALE"));
        checks.add(new TestSignalCheckResult(
                "stale_state",
                "No stale state",
                staleState ? "WARNING" : "SUCCESS",
                staleState ? "Market freshness is stale" : "No stale state detected",
                staleState ? "Verify market feed freshness and ingestion lag" : null,
                staleState ? "OPEN_MARKET_FEED" : null
        ));
        return checks;
    }

    private String findPreOrderBlockReason(AdminTestSignalRun run) {
        try {
            Object[] row = (Object[]) entityManager.createNativeQuery("""
                    select rule_code, message
                    from risk_events
                    where deleted = false
                      and user_id = :userId
                      and order_id is null
                      and created_at >= :startedAt
                    order by created_at desc
                    limit 1
                    """)
                    .setParameter("userId", run.getTraderUserId())
                    .setParameter("startedAt", run.getStartedAt())
                    .getResultStream()
                    .findFirst()
                    .orElse(null);
            if (row == null) {
                return null;
            }
            String code = row[0] == null ? "" : String.valueOf(row[0]);
            String msg = row[1] == null ? "" : String.valueOf(row[1]);
            if (!code.isBlank() && !msg.isBlank()) {
                return code + " - " + msg;
            }
            return !msg.isBlank() ? msg : code;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static TestSignalCheckResult check(
            String key,
            String label,
            boolean ok,
            String okMessage,
            String failMessage,
            String action,
            String actionCode
    ) {
        return new TestSignalCheckResult(
                key,
                label,
                ok ? "SUCCESS" : "FAILED",
                ok ? okMessage : failMessage,
                ok ? null : action,
                ok ? null : actionCode
        );
    }

    /** Non-blocking check: failures surface as WARNING so test lab can still pass core execution. */
    private static TestSignalCheckResult optionalCheck(
            String key,
            String label,
            boolean ok,
            String okMessage,
            String failMessage,
            String action,
            String actionCode
    ) {
        return new TestSignalCheckResult(
                key,
                label,
                ok ? "SUCCESS" : "WARNING",
                ok ? okMessage : failMessage,
                ok ? null : action,
                ok ? null : actionCode
        );
    }

    private static boolean isBrokerAcceptedState(String state, boolean liveMode, OmsOrder order) {
        if (state == null) {
            return false;
        }
        if (liveMode && order != null) {
            if (order.getExecutionMode() != ExecutionMode.LIVE) {
                return false;
            }
            if (order.getBrokerExternalOrderId() == null || order.getBrokerExternalOrderId().isBlank()) {
                return false;
            }
        }
        return switch (state) {
            case "ACCEPTED", "FILLED", "PARTIALLY_FILLED" -> true;
            case "SUBMITTED" -> liveMode;
            default -> false;
        };
    }

    private boolean isLiveBrokerFill(OmsOrder order, boolean liveMode) {
        if (order == null) {
            return false;
        }
        if (liveMode) {
            boolean omsFilled = order.getExecutionMode() == ExecutionMode.LIVE
                    && order.getBrokerExternalOrderId() != null
                    && !order.getBrokerExternalOrderId().isBlank()
                    && ("FILLED".equals(order.getState().name()) || "PARTIALLY_FILLED".equals(order.getState().name()));
            if (omsFilled) {
                return true;
            }
            return isKiteOrderComplete(order.getUserId(), order.getBrokerExternalOrderId());
        }
        return "FILLED".equals(order.getState().name()) || "PARTIALLY_FILLED".equals(order.getState().name());
    }

    private boolean isLivePositionVerified(OmsOrder order, AdminTestSignalRun run) {
        if (order == null) {
            return false;
        }
        if (isLiveBrokerFill(order, true)) {
            return true;
        }
        // MIS round-trip: entry fills then square-off closes position ??? Kite COMPLETE on both legs is sufficient.
        if (run != null && "COMPLETED".equalsIgnoreCase(run.getSquareOffStatus())) {
            boolean entryComplete = isKiteOrderComplete(order.getUserId(), order.getBrokerExternalOrderId());
            if (!entryComplete) {
                return false;
            }
            if (run.getSquareOffOrderId() == null) {
                return true;
            }
            return omsOrderRepository.findById(run.getSquareOffOrderId())
                    .map(exit -> isKiteOrderComplete(exit.getUserId(), exit.getBrokerExternalOrderId()))
                    .orElse(false);
        }
        return false;
    }

    private boolean isKiteOrderComplete(UUID userId, String kiteOrderId) {
        if (userId == null || kiteOrderId == null || kiteOrderId.isBlank()) {
            return false;
        }
        try {
            return zerodhaBrokerOperationsService.recentOrders(userId, 300).stream()
                    .filter(o -> kiteOrderId.equals(o.orderId()))
                    .anyMatch(o -> {
                        String status = o.status() != null ? o.status().trim().toUpperCase() : "";
                        return "COMPLETE".equals(status) || "COMPLETED".equals(status);
                    });
        } catch (Exception ex) {
            log.debug("test.lab.kite_status_check_failed userId={} kiteOrderId={} {}", userId, kiteOrderId, ex.getMessage());
            return false;
        }
    }

    private StrategySignalEntity previewSignal(TestSignalLabRequest request, UUID traderUserId, String strategyKey) {
        StrategySignalEntity preview = new StrategySignalEntity();
        preview.setUserId(traderUserId);
        preview.setStrategyName(strategyKey);
        preview.setSymbol(resolveNormalizedSymbol(request));
        preview.setSignalType("SELL".equalsIgnoreCase(request.side()) ? SignalType.SELL : SignalType.BUY);
        preview.setTestTrade(true);
        preview.setCandleTimestamp(Instant.now());
        return preview;
    }

    private Map<String, Object> buildDiagnostics(List<TestSignalCheckResult> checks, AdminTestSignalRun run, OmsOrder order, Map<String, Object> healthSnapshot) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("failedChecks", checks.stream().filter(c -> "FAILED".equals(c.status())).toList());
        diagnostics.put("warnings", checks.stream().filter(c -> "WARNING".equals(c.status())).toList());
        diagnostics.put("recoverySuggestions", checks.stream()
                .filter(c -> c.suggestedAction() != null && !c.suggestedAction().isBlank())
                .map(c -> Map.of("check", c.label(), "action", c.suggestedAction(), "code", c.actionCode()))
                .toList());
        diagnostics.put("testScenario", resolveScenario(run));
        diagnostics.put("orderState", order != null && order.getState() != null ? order.getState().name() : null);
        diagnostics.put("effectiveExecutionMode", order != null && order.getExecutionMode() != null ? order.getExecutionMode().name() : null);
        diagnostics.put("kiteOrderId", order != null ? order.getBrokerExternalOrderId() : null);
        diagnostics.put("health", healthSnapshot);
        return diagnostics;
    }

    private boolean hasExecutionAlerts(UUID orderId) {
        Number n = (Number) entityManager.createNativeQuery(
                        "select count(*) from execution_alert_log where deleted = false and order_id = :orderId")
                .setParameter("orderId", orderId)
                .getSingleResult();
        return n != null && n.longValue() > 0;
    }

    private boolean hasReconciliationEvent(UUID orderId) {
        Number n = (Number) entityManager.createNativeQuery(
                        "select count(*) from reconciliation_events where deleted = false and order_id = :orderId")
                .setParameter("orderId", orderId)
                .getSingleResult();
        return n != null && n.longValue() > 0;
    }

    private static boolean readPath(Map<String, Object> healthSnapshot, String section, String key, String expected) {
        Object sec = healthSnapshot.get(section);
        if (!(sec instanceof Map<?, ?> m)) return false;
        Object val = m.get(key);
        return val != null && expected.equalsIgnoreCase(String.valueOf(val));
    }

    private static String overallStatus(List<TestSignalCheckResult> checks) {
        boolean hasFailed = checks.stream().anyMatch(c -> "FAILED".equals(c.status()));
        if (hasFailed) {
            return "FAILED";
        }
        boolean coreOk = checks.stream()
                .filter(c -> !isOptionalLabCheck(c.key()))
                .allMatch(c -> "SUCCESS".equals(c.status()));
        return coreOk ? "SUCCESS" : "WARNING";
    }

    private static boolean isOptionalLabCheck(String key) {
        return "telegram_alert".equals(key)
                || "reconciliation".equals(key)
                || "stale_state".equals(key)
                || "latency".equals(key)
                || "market_session".equals(key);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }

    private TestSignalExecutionReport reportFrom(
            AdminTestSignalRun run,
            List<TestSignalTimelineEvent> timeline,
            List<TestSignalCheckResult> checks,
            Map<String, Object> healthSnapshot,
            Map<String, Object> diagnostics
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("testId", run.getId());
        summary.put("traderUserId", run.getTraderUserId());
        summary.put("brokerVendor", run.getBrokerVendor());
        summary.put("strategy", run.getStrategyKey());
        summary.put("symbol", run.getSymbol());
        summary.put("qty", run.getQuantity());
        summary.put("mode", run.getExecutionMode());
        summary.put("productType", run.getProductType());
        summary.put("squareOffStatus", run.getSquareOffStatus());
        summary.put("latencyMs", run.getTotalLatencyMs());
        summary.put("result", run.getFinalStatus());
        summary.put("executionResult", run.getStatus());

        return new TestSignalExecutionReport(
                run.getId(),
                run.getStatus(),
                run.getFinalStatus(),
                run.getSignalId(),
                run.getOrderId(),
                run.getTotalLatencyMs(),
                summary,
                timeline,
                checks,
                healthSnapshot,
                diagnostics
        );
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private List<TestSignalTimelineEvent> readTimeline(AdminTestSignalRun run) {
        try {
            Map<String, Object> report = objectMapper.readValue(run.getReportJson(), MAP_TYPE);
            Object raw = report.get("timeline");
            if (!(raw instanceof List<?> list)) return List.of();
            List<TestSignalTimelineEvent> out = new ArrayList<>();
            for (Object obj : list) {
                if (!(obj instanceof Map<?, ?> m)) continue;
                out.add(new TestSignalTimelineEvent(
                        String.valueOf(m.get("stage")),
                        m.get("at") == null ? null : Instant.parse(String.valueOf(m.get("at"))),
                        m.get("detail") == null ? null : String.valueOf(m.get("detail"))
                ));
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<TestSignalCheckResult> readChecks(AdminTestSignalRun run) {
        try {
            Map<String, Object> report = objectMapper.readValue(run.getReportJson(), MAP_TYPE);
            Object raw = report.get("checks");
            if (!(raw instanceof List<?> list)) return List.of();
            List<TestSignalCheckResult> out = new ArrayList<>();
            for (Object obj : list) {
                if (!(obj instanceof Map<?, ?> m)) continue;
                out.add(new TestSignalCheckResult(
                        String.valueOf(m.get("key")),
                        String.valueOf(m.get("label")),
                        String.valueOf(m.get("status")),
                        m.get("message") == null ? null : String.valueOf(m.get("message")),
                        m.get("suggestedAction") == null ? null : String.valueOf(m.get("suggestedAction")),
                        m.get("actionCode") == null ? null : String.valueOf(m.get("actionCode"))
                ));
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, Object> readHealth(AdminTestSignalRun run) {
        try {
            Map<String, Object> report = objectMapper.readValue(run.getReportJson(), MAP_TYPE);
            Object raw = report.get("health");
            if (raw instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
            return Map.of();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDiagnostics(AdminTestSignalRun run) {
        try {
            return objectMapper.readValue(run.getDiagnosticsJson(), MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
