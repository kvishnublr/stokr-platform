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
import com.stokr.execution.pipeline.OrderIntentProcessor;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.BrokerAccountRepository;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
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
        run.setSymbol(request.symbol().trim().toUpperCase());
        run.setSide(request.side().trim().toUpperCase());
        run.setQuantity(resolveQuantity(request.quantity(), request.forceQuantityOne()));
        run.setProductType(request.productType());
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
        run.setAutoSquareOffMinutes(request.autoSquareOffMinutes());
        if (request.autoSquareOffMinutes() != null && request.autoSquareOffMinutes() > 0) {
            run.setAutoSquareOffDueAt(Instant.now().plus(Duration.ofMinutes(request.autoSquareOffMinutes())));
            run.setSquareOffStatus("PENDING");
        }
        run.setStatus("RUNNING");
        run.setStartedAt(Instant.now());
        run = runRepository.save(run);

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

        String correlationId = "test-lab:" + run.getId();
        Instant start = Instant.now();

        // Persist signal directly — bypass RabbitMQ for test lab runs.
        // This eliminates both queue hops and makes execution synchronous:
        //   signal save → risk → execution → broker → all within this transaction.
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
            // synchronous=true → no execution queue, runs inline in this transaction
            orderIntentProcessor.processSignalIntent(msg, true);
        } catch (Exception ex) {
            log.error("test.lab.sync_execution_error runId={} signal={} reason={}",
                    run.getId(), savedSignal.getId(), ex.getMessage(), ex);
            // Rethrow to prevent transaction rollback-only state
            throw new IllegalStateException("Signal processing failed: " + ex.getMessage(), ex);
        }

        // Order was created synchronously — short poll to allow Hibernate flush
        Optional<OmsOrder> order = waitForOrder(run.getSignalId(), run.getTraderUserId(), Duration.ofSeconds(3));
        order = reconcileResolvedOrder(run, order);

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
                        "displayName", s.getDisplayName() == null ? s.getStrategyKey() : s.getDisplayName()
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

    private Optional<OmsOrder> waitForOrder(UUID signalId, UUID traderUserId, Duration timeout) {
        if (signalId == null) return Optional.empty();
        Instant until = Instant.now().plus(timeout);
        Optional<OmsOrder> order;
        do {
            order = omsOrderRepository.findFirstBySignalIdAndUserIdAndDeletedFalseOrderByCreatedAtDesc(signalId, traderUserId);
            if (order.isPresent()) return order;
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        } while (Instant.now().isBefore(until));
        return Optional.empty();
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
            OmsOrder resolved = candidates.get(0);
            run.setOrderId(resolved.getId());
            return Optional.of(resolved);
        }
        return Optional.empty();
    }

    private static Duration resolveOrderWaitTimeout(AdminTestSignalRun run) {
        if (run == null) {
            return Duration.ofSeconds(15);
        }
        if (run.isDryRunOnly() || "SIMULATED".equalsIgnoreCase(run.getExecutionMode())) {
            return Duration.ofSeconds(2);
        }
        if ("LIVE".equalsIgnoreCase(run.getExecutionMode()) || "BOTH".equalsIgnoreCase(run.getExecutionMode())) {
            return Duration.ofSeconds(60);
        }
        return Duration.ofSeconds(20);
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

        boolean brokerAccepted = order.map(o -> "ACCEPTED".equals(o.getState().name()) || "FILLED".equals(o.getState().name()) || "PARTIALLY_FILLED".equals(o.getState().name())).orElse(false);
        String brokerFailMsg = "Broker did not accept";
        if (order.isPresent() && order.get().getRejectReason() != null && !order.get().getRejectReason().isBlank()) {
            brokerFailMsg = order.get().getRejectReason();
        } else if (order.isPresent() && "FAILED".equals(order.get().getState().name())) {
            brokerFailMsg = "Broker rejected order (no reason recorded)";
        }
        checks.add(check("broker_accepted", "Broker Accepted", !expectsOrder || brokerAccepted, "Broker accepted execution", brokerFailMsg, "Review broker auth/session in Broker Infra", "RECONNECT_BROKER"));

        boolean filled = order.map(o -> "FILLED".equals(o.getState().name()) || "PARTIALLY_FILLED".equals(o.getState().name())).orElse(false);
        checks.add(check("order_filled", "Order Filled", !expectsOrder || filled, "Order filled", "Order not filled yet", "Inspect execution timeline for pending state", "OPEN_EXECUTION_TIMELINE"));

        boolean positionVisible = portfolioPositionRepository.findByUserIdAndSymbolAndDeletedFalse(run.getTraderUserId(), run.getSymbol())
                .map(PortfolioPosition::getQuantity)
                .map(q -> q.signum() != 0)
                .orElse(false);
        checks.add(check("position_visible", "Position Visible", !expectsOrder || positionVisible, "Position visible in terminal", "No position entry yet", "Open trader positions and verify sync", "OPEN_TRADER_POSITIONS"));

        boolean websocketOpen = platformBrokerFeedSessionRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(run.getBrokerVendor() == null ? "ZERODHA" : run.getBrokerVendor())
                .map(s -> "OPEN".equalsIgnoreCase(s.getWebsocketState()))
                .orElse(false);
        checks.add(check("websocket", "Websocket Delivered", websocketOpen, "Broker websocket OPEN", "Broker websocket not open", "Reconnect broker websocket session", "RECONNECT_BROKER"));

        boolean telegramLogged = order.map(o -> hasExecutionAlerts(o.getId())).orElse(false);
        checks.add(check("telegram_alert", "Telegram Sent", !expectsOrder || telegramLogged, "Alert log entry found", "No alert log entry yet", "Check notification providers and execution alerts", "OPEN_ALERTS"));

        boolean reconciled = order.map(o -> hasReconciliationEvent(o.getId())).orElse(false);
        checks.add(check("reconciliation", "Reconciliation Successful", !expectsOrder || reconciled, "Reconciliation event found", "No reconciliation event found", "Check reconciliation worker and broker adapter", "OPEN_RECONCILIATION"));

        long latency = run.getTotalLatencyMs() == null ? 0L : run.getTotalLatencyMs();
        boolean latencyOk = latency == 0 || latency < 15_000;
        checks.add(check("latency", "Latency Acceptable", latencyOk, "Latency within threshold", "High end-to-end latency", "Inspect Rabbit queue and DB load", "OPEN_INFRA_HEALTH"));

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
        if (hasFailed) return "FAILED";
        boolean hasWarning = checks.stream().anyMatch(c -> "WARNING".equals(c.status()));
        return hasWarning ? "WARNING" : "SUCCESS";
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
