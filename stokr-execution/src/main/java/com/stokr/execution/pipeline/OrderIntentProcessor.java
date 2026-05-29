package com.stokr.execution.pipeline;

import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.common.telemetry.SignalDistributionTelemetryService;
import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.safety.OmsSafetyGateService;
import com.stokr.execution.comparison.ExecutionComparisonService;
import com.stokr.execution.guard.ExecutionGuardMode;
import com.stokr.execution.guard.ExecutionGuardViolation;
import com.stokr.execution.risk.RiskContextFactory;
import com.stokr.execution.capital.StrategyCapitalReservationService;
import com.stokr.execution.sizing.PositionSizingRejectedException;
import com.stokr.execution.sizing.PositionSizingRequest;
import com.stokr.execution.sizing.PositionSizingResult;
import com.stokr.execution.sizing.PositionSizingService;
import com.stokr.execution.sizing.PositionSizingTelemetryService;
import com.stokr.execution.service.ExecutionService;
import com.stokr.oms.domain.ExecutionEventType;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.trace.ExecutionTraceService;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.common.notification.NotificationEvent;
import com.stokr.common.notification.NotificationPublisher;
import com.stokr.risk.model.LiveTraderEligibilityResult;
import com.stokr.risk.service.LiveTradingTraderEligibilityService;
import com.stokr.risk.service.RiskEngineService;
import com.stokr.risk.service.RiskEvaluationTraceService;
import com.stokr.risk.service.RiskEventRecorder;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import com.stokr.user.service.TraderExecutionModePreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIntentProcessor {

    private final StrategySignalRepository signalRepository;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final RiskEngineService riskEngineService;
    private final RiskEvaluationTraceService riskEvaluationTraceService;
    private final RiskEventRecorder riskEventRecorder;
    private final ExecutionService executionService;
    private final LiveTradingTraderEligibilityService liveTradingTraderEligibilityService;
    private final ObjectProvider<NotificationPublisher> notificationPublisher;
    private final ExecutionTraceService executionTraceService;
    private final RiskContextFactory riskContextFactory;
    private final SignalDistributionTelemetryService signalDistributionTelemetryService;
    private final PositionSizingService positionSizingService;
    private final PositionSizingTelemetryService positionSizingTelemetryService;
    private final StrategyCapitalReservationService capitalReservationService;
    private final ExecutionComparisonService executionComparisonService;
    private final StrategyExecutionConfigService strategyExecutionConfigService;
    private final BrokerPositionTruthService brokerPositionTruthService;
    private final TraderExecutionModePreferenceService traderExecutionModePreferenceService;
    private final OmsSafetyGateService omsSafetyGateService;

    @Value("${stokr.risk.zone:Asia/Kolkata}")
    private String riskZone;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    /**
     * Creates OMS order from persisted signal, runs risk, emits execution events, then routes to execution (sync or async).
     */
    @Transactional
    public void processSignalIntent(SignalPersistedMessage msg, boolean synchronousExecution) {
        StrategySignalEntity signal = signalRepository.findById(msg.signalId())
                .orElseThrow(() -> new IllegalStateException("Signal not found: " + msg.signalId()));

        if (signal.getSignalType() == SignalType.HOLD) {
            log.debug("signal.hold.skip signalId={}", signal.getId());
            return;
        }

        // Guard: block execution for deleted or disabled strategies (critical safety check)
        if (!Boolean.TRUE.equals(signal.getTestTrade())) {
            String sigStrategyKey = signal.getStrategyName() != null
                    ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;
            java.util.Optional<StrategyDefinition> defOpt =
                    strategyDefinitionRepository.findByStrategyKeyAndDeletedFalse(sigStrategyKey);
            if (defOpt.isEmpty()) {
                log.warn("signal.blocked.strategy_not_found signalId={} strategyKey={}",
                        signal.getId(), sigStrategyKey);
                signalDistributionTelemetryService.recordGateRejected(
                        signal.getUserId(), signal.getId(), "STRATEGY_NOT_FOUND");
                return;
            }
            StrategyDefinition def = defOpt.get();
            if (!def.isEnabled()) {
                log.warn("signal.blocked.strategy_disabled signalId={} strategyKey={}",
                        signal.getId(), sigStrategyKey);
                signalDistributionTelemetryService.recordGateRejected(
                        signal.getUserId(), signal.getId(), "STRATEGY_DISABLED");
                return;
            }
        }

        // Use trader userId from message (fan-out path), fall back to signal entity userId
        UUID userId = resolveUserId(msg, signal);
        boolean isSystemUser = systemUserId.equals(userId);
        // Include userId in idempotency key so each trader gets their own order per signal
        String idempotencyKey = "signal:" + signal.getId() + ":" + userId;
        String strategyKey =
                signal.getStrategyName() != null ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;

        // Resolve effective execution mode: strategy config takes precedence over the poll/message mode.
        ExecutionMode mode = resolveEffectiveMode(msg.executionMode(), strategyKey, userId, signal);
        mode = applyTraderExecutionPreference(mode, userId, isSystemUser);

        Instant safetyNow = Instant.now();
        OmsSafetyGateService.OmsSafetyGateResult safety =
                omsSafetyGateService.evaluatePreOrder(signal, userId, mode, safetyNow);
        mode = safety.effectiveMode();
        if (safety.blocked() && shouldRejectOrder(safety.blockCode())) {
            log.warn("order.intent.safety_rejected signalId={} code={} message={}",
                    signal.getId(), safety.blockCode(), safety.blockMessage());
            riskEventRecorder.record(userId, null, safety.blockCode(), "REJECT", safety.blockMessage());
            signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), safety.blockCode());
            return;
        }
        if (!safety.reasons().isEmpty()) {
            log.info("order.intent.safety_adjusted signalId={} mode={} reasons={}",
                    signal.getId(), mode, safety.reasons());
        }

        log.info("order.intent.mode_resolved signalId={} msgMode={} resolvedMode={} isTestTrade={}",
                signal.getId(), msg.executionMode(), mode, Boolean.TRUE.equals(signal.getTestTrade()));

        // System-generated signals bypass paper/SIM user-level gate (no trader account needed).
        // For LIVE mode: system signals only check platform gates (kill switch, live armed).
        // Real trader signals run the full user eligibility gate (broker, onboarding, etc).
        if (mode == ExecutionMode.LIVE) {
            if (isSystemUser) {
                LiveTraderEligibilityResult platformGate =
                        liveTradingTraderEligibilityService.evaluateForLiveStrategyActivation(userId, strategyKey, "ZERODHA");
                if (!platformGate.allowed()) {
                    log.warn("live.order.blocked.platform signalId={} reason={}", signal.getId(), platformGate.reasonCode());
                    riskEventRecorder.record(userId, null, platformGate.reasonCode(), "REJECT", platformGate.message());
                    signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), "LIVE_PLATFORM_GATE");
                    return;
                }
            } else {
                LiveTraderEligibilityResult gate = Boolean.TRUE.equals(signal.getTestTrade())
                        ? liveTradingTraderEligibilityService.evaluateForLiveStrategyActivation(userId, strategyKey, "ZERODHA")
                        : liveTradingTraderEligibilityService.evaluateForLiveOrder(userId, strategyKey, "ZERODHA");
                if (!gate.allowed()) {
                    log.warn("live.order.blocked.pre_signal signalId={} reason={}", signal.getId(), gate.reasonCode());
                    riskEventRecorder.record(userId, null, gate.reasonCode(), "REJECT", gate.message());
                    notifyEligibility(userId, gate);
                    signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), "LIVE_GATE");
                    return;
                }
            }
        } else if (!isSystemUser) {
            LiveTraderEligibilityResult paper = liveTradingTraderEligibilityService.evaluateForPaperTrading(strategyKey);
            if (!paper.allowed()) {
                log.warn("paper.order.blocked.pre_signal signalId={} reason={}", signal.getId(), paper.reasonCode());
                riskEventRecorder.record(userId, null, paper.reasonCode(), "REJECT", paper.message());
                notifyEligibility(userId, paper);
                signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), "PAPER_GATE");
                return;
            }
        }

        if (mode == ExecutionMode.BOTH) {
            dispatchBothMode(signal, userId, idempotencyKey, strategyKey, synchronousExecution, safetyNow);
            return;
        }

        PositionSizingResult sizing;
        try {
            sizing = resolveSizing(signal, userId, mode);
        } catch (PositionSizingRejectedException ex) {
            log.warn("order.intent.sizing_rejected signalId={} reason={}", signal.getId(), ex.getMessage());
            riskEventRecorder.record(userId, null, "SIZING_REJECTED", "REJECT", ex.getMessage());
            signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), "SIZING_REJECTED");
            return;
        }

        OmsOrder draft = buildDraftFromSignal(signal, mode, userId, sizing);
        OmsSafetyGateService.OmsSafetyGateResult exposure =
                omsSafetyGateService.evaluateExposure(draft, userId, safetyNow);
        if (exposure.blocked()) {
            log.warn("order.intent.exposure_rejected signalId={} code={}", signal.getId(), exposure.blockCode());
            recordSizingTelemetry(signal, userId, mode, sizing, null);
            riskEventRecorder.record(userId, null, exposure.blockCode(), "REJECT", exposure.blockMessage());
            signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), exposure.blockCode());
            return;
        }

        OmsOrder order = orderLifecycleService.createOrGetIdempotent(userId, idempotencyKey, draft);
        if (order.getState() != OrderState.CREATED) {
            log.info("order.idempotent.hit orderId={} state={}", order.getId(), order.getState());
            signalDistributionTelemetryService.recordIdempotentHit(userId);
            return;
        }

        capitalReservationService.reserve(
                strategyKey, userId, signal.getId(), order.getId(), signal.getSymbol(), sizing);
        recordSizingTelemetry(signal, userId, mode, sizing, order.getId());

        if (mode == ExecutionMode.LIVE
                && !Boolean.TRUE.equals(signal.getTestTrade())
                && !omsSafetyGateService.acquireLiveDedupe(signal, userId, order.getId(), mode, safetyNow)) {
            capitalReservationService.releaseByOrder(order.getId(), "DUPLICATE_EXECUTION_KEY");
            orderLifecycleService.transition(order.getId(), OrderState.REJECTED, "DUPLICATE_EXECUTION_KEY");
            signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), "DUPLICATE_EXECUTION_KEY");
            return;
        }

        String testScenario = normalizeTestScenario(signal);
        if ("SIMULATE_MARGIN_FAILURE".equals(testScenario)) {
            rejectForTestScenario(order, "Test scenario: insufficient margin");
            signalDistributionTelemetryService.recordRiskRejected(userId, signal.getId());
            return;
        }
        if ("SIMULATE_REJECTION".equals(testScenario)) {
            rejectForTestScenario(order, "Test scenario: broker rejection");
            return;
        }
        if ("SIMULATE_BROKER_DISCONNECT".equals(testScenario)) {
            rejectForTestScenario(order, "Test scenario: broker disconnected");
            return;
        }

        signalDistributionTelemetryService.recordOrderCreatedFromSignal(userId, order.getId(), signal.getId());
        executionTraceService.trace(order, ExecutionEventType.SIGNAL_GENERATED, Map.of(
                "signalId", signal.getId().toString(),
                "symbol", signal.getSymbol() != null ? signal.getSymbol() : ""
        ));

        executionTraceService.trace(order, ExecutionEventType.ORDER_REQUESTED, Map.of(
                "symbol", signal.getSymbol() != null ? signal.getSymbol() : "",
                "side", mapSide(signal),
                "executionMode", mode.name()
        ));

        order = orderLifecycleService.transition(order.getId(), OrderState.VALIDATED, null);
        order = orderLifecycleService.transition(order.getId(), OrderState.RISK_CHECK, null);

        ZoneId zone = ZoneId.of(riskZone);
        Instant evalInstant = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : Instant.now();
        BigDecimal atrRatio = atrToCloseRatio(signal);
        RiskContext ctx = riskContextFactory.build(userId, order, zone, evalInstant, atrRatio);

        RiskDecision decision = riskEngineService.evaluate(ctx);
        riskEvaluationTraceService.record(ctx, decision, order.getId());
        if (!decision.allowed()) {
            capitalReservationService.releaseByOrder(order.getId(), "RISK_REJECT");
            riskEventRecorder.record(
                    userId,
                    order.getId(),
                    decision.reasonCode() != null ? decision.reasonCode() : "RISK",
                    "REJECT",
                    decision.message()
            );
            order = orderLifecycleService.transition(order.getId(), OrderState.REJECTED, decision.message());
            executionTraceService.trace(order, ExecutionEventType.EXECUTION_REJECTED, Map.of(
                    "phase", "RISK",
                    "reason", decision.message() != null ? decision.message() : ""
            ));
            signalDistributionTelemetryService.recordRiskRejected(userId, signal.getId());
            return;
        }

        executionTraceService.trace(order, ExecutionEventType.RISK_CHECK_PASSED, Map.of(
                "riskReason", decision.message() != null ? decision.message() : "OK"
        ));

        order = orderLifecycleService.transition(order.getId(), OrderState.PENDING_SUBMISSION, null);

        if (mode == ExecutionMode.LIVE) {
            brokerPositionTruthService.syncUser(userId);
            String side = order.getSide();
            ExecutionGuardMode guardMode = "SELL".equalsIgnoreCase(side)
                    ? ExecutionGuardMode.EXIT_SAFE
                    : ExecutionGuardMode.ENTRY_STRICT;
            var brokerViolations = brokerPositionTruthService.validateForExecution(
                    userId, order.getSymbol(), side, guardMode, Instant.now());
            if (!brokerViolations.isEmpty()) {
                ExecutionGuardViolation v = brokerViolations.getFirst();
                order = orderLifecycleService.transition(order.getId(), OrderState.REJECTED, v.message());
                executionTraceService.trace(order, ExecutionEventType.EXECUTION_REJECTED, Map.of(
                        "phase", "BROKER_TRUTH",
                        "code", v.code(),
                        "detail", v.detail() != null ? v.detail() : ""
                ));
                signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), v.code());
                return;
            }
        }

        if ("SIMULATE_TIMEOUT".equals(testScenario)) {
            log.info("test.signal.timeout_simulated signalId={} orderId={}", signal.getId(), order.getId());
            return;
        }

        log.info("order.intent.before_dispatch orderId={} executionMode={} state={} symbol={}",
                order.getId(), order.getExecutionMode(), order.getState(), order.getSymbol());

        long fillKey = fillDeterminismKey(signal);
        Instant anchor = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : order.getCreatedAt();

        executionService.dispatch(
                new ExecutionDispatchMessage(
                        order.getId(),
                        order.getUserId(),
                        signal.getId(),
                        order.getBrokerVendor(),
                        0,
                        signal.getBacktestRunId(),
                        msg.executionMode(),
                        fillKey,
                        anchor
                ),
                synchronousExecution
        );
        executionTraceService.trace(order, ExecutionEventType.EXECUTION_DISPATCHED, Map.of(
                "channel", synchronousExecution ? "SYNC" : "RABBIT_EXECUTION",
                "attempt", 0
        ));
    }

    private static long fillDeterminismKey(StrategySignalEntity signal) {
        long k = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp().toEpochMilli() : 0L;
        k ^= signal.getId().getMostSignificantBits();
        k ^= signal.getId().getLeastSignificantBits();
        if (signal.getUserId() != null) {
            k ^= signal.getUserId().getMostSignificantBits();
        }
        return k;
    }

    private OmsOrder buildDraftFromSignal(
            StrategySignalEntity signal, ExecutionMode mode, UUID userId, PositionSizingResult sizing) {
        OmsOrder o = new OmsOrder();
        o.setSignalId(signal.getId());
        o.setStrategyKey(signal.getStrategyName() != null ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY);
        o.setExecutionMode(mode);
        log.info("order.draft.created symbol={} mode={} side={} qty={}",
                signal.getSymbol(), mode, mapSide(signal), sizing.normalizedQuantity());
        o.setSymbol(normalizeTestLabSymbol(signal));
        o.setSide(mapSide(signal));
        o.setOrderType("MARKET");
        o.setQuantity(sizing.normalizedQuantity());
        o.setLimitPrice(null);
        o.setStopPrice(signal.getStopPrice());
        o.setTargetPrice(signal.getTargetPrice());
        o.setEntryReferencePrice(signal.getEntryReferencePrice());
        o.setBacktestRunId(signal.getBacktestRunId());
        o.setBrokerVendor(mode == ExecutionMode.LIVE ? "ZERODHA" : "SIM");
        o.setTestTrade(Boolean.TRUE.equals(signal.getTestTrade()));
        o.setTestRunId(signal.getTestRunId());
        if (signal.isSimulation()) {
            o.setSimulation(true);
            o.setSimulationRunId(signal.getSimulationRunId());
            o.setSimulationScenario(signal.getSimulationScenario());
        }
        return o;
    }

    private static BigDecimal atrToCloseRatio(StrategySignalEntity signal) {
        if (signal.getAtrValue() == null || signal.getEntryReferencePrice() == null) {
            return null;
        }
        if (signal.getEntryReferencePrice().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return signal.getAtrValue().divide(signal.getEntryReferencePrice(), 8, RoundingMode.HALF_UP);
    }

    private static String normalizeTestLabSymbol(StrategySignalEntity signal) {
        String symbol = signal.getSymbol();
        if (!Boolean.TRUE.equals(signal.getTestTrade()) || symbol == null || symbol.isBlank()) {
            return symbol;
        }
        String trimmed = symbol.trim().toUpperCase();
        if (trimmed.contains(":")) {
            return trimmed;
        }
        return "NSE:" + trimmed;
    }

    private static String mapSide(StrategySignalEntity signal) {
        return switch (signal.getSignalType()) {
            case BUY -> "BUY";
            case SELL -> "SELL";
            case EXIT -> "SELL";
            case HOLD -> "HOLD";
        };
    }

    private static String normalizeTestScenario(StrategySignalEntity signal) {
        if (!Boolean.TRUE.equals(signal.getTestTrade()) || signal.getTestScenario() == null) {
            return "";
        }
        return signal.getTestScenario().trim().toUpperCase();
    }

    /** Test-lab simulate-* flags must follow CREATED → VALIDATED → RISK_CHECK → REJECTED (not CREATED → REJECTED). */
    private void rejectForTestScenario(OmsOrder order, String message) {
        orderLifecycleService.transition(order.getId(), OrderState.VALIDATED, null);
        orderLifecycleService.transition(order.getId(), OrderState.RISK_CHECK, null);
        orderLifecycleService.transition(order.getId(), OrderState.REJECTED, message);
    }

    /**
     * Resolves the effective execution mode for this signal.
     * Strategy execution config takes precedence over the message/poll mode.
     * Falls back to message mode if no config or config has no explicit mode.
     */
    private ExecutionMode resolveEffectiveMode(String msgMode, String strategyKey, UUID userId, StrategySignalEntity signal) {
        if (Boolean.TRUE.equals(signal.getTestTrade())) {
            return parseMode(msgMode);
        }
        try {
            java.util.Optional<StrategyExecutionConfig> cfgOpt =
                    strategyExecutionConfigService.getByStrategyKeyForUser(userId, strategyKey);
            if (cfgOpt.isPresent()) {
                StrategyExecutionConfig cfg = cfgOpt.get();
                if (!cfg.isEnabled()) {
                    log.debug("signal.strategy_disabled strategyKey={}", strategyKey);
                    return ExecutionMode.SIMULATED; // will be a no-op effectively — risk will reject
                }
                ExecutionMode cfgMode = parseMode(cfg.getExecutionMode());
                if (cfgMode != ExecutionMode.SIMULATED) {
                    log.debug("signal.mode_from_config strategyKey={} mode={}", strategyKey, cfgMode);
                    return cfgMode;
                }
            }
        } catch (Exception ex) {
            log.warn("signal.mode_resolve_failed strategyKey={} — falling back to msg mode", strategyKey, ex);
        }
        return parseMode(msgMode);
    }

    private ExecutionMode applyTraderExecutionPreference(ExecutionMode mode, UUID userId, boolean isSystemUser) {
        if (isSystemUser || mode == ExecutionMode.BOTH) {
            return mode;
        }
        if (mode == ExecutionMode.SIMULATED) {
            return ExecutionMode.PAPER;
        }
        if (mode == ExecutionMode.LIVE) {
            String pref = traderExecutionModePreferenceService.get(userId).executionMode();
            if (!"LIVE".equalsIgnoreCase(pref)) {
                log.info("order.intent.mode_capped_to_paper userId={} workspacePref={}", userId, pref);
                return ExecutionMode.PAPER;
            }
        }
        return mode;
    }

    private static boolean shouldRejectOrder(String blockCode) {
        if (blockCode == null) {
            return false;
        }
        return switch (blockCode) {
            case "STALE_SIGNAL", "STALE_SIGNAL_UNKNOWN", "DUPLICATE_EXECUTION_KEY",
                 "MARKET_CLOSE_NO_NEW_ENTRIES" -> true;
            default -> false;
        };
    }

    private static ExecutionMode parseMode(String executionMode) {
        if (executionMode == null || executionMode.isBlank()) {
            return ExecutionMode.PAPER;
        }
        String m = executionMode.trim().toUpperCase();
        if ("LIVE".equals(m)) {
            return ExecutionMode.LIVE;
        }
        if ("PAPER".equals(m)) {
            return ExecutionMode.PAPER;
        }
        if ("BOTH".equals(m)) {
            return ExecutionMode.BOTH;
        }
        if ("SIMULATED".equals(m)) {
            return ExecutionMode.PAPER;
        }
        return ExecutionMode.PAPER;
    }

    private UUID resolveUserId(SignalPersistedMessage msg, StrategySignalEntity signal) {
        // Prefer the trader userId sent by the fan-out, fall back to signal entity userId
        if (msg.userId() != null && !systemUserId.equals(msg.userId())) {
            return msg.userId();
        }
        return signal.getUserId() != null ? signal.getUserId() : systemUserId;
    }

    private void notifyEligibility(UUID userId, LiveTraderEligibilityResult gate) {
        notificationPublisher.ifAvailable(pub -> pub.publish(new NotificationEvent(
                "IN_APP",
                "TRADER_ELIGIBILITY_BLOCK",
                userId,
                new LinkedHashMap<>(Map.of(
                        "reasonCode", gate.reasonCode() != null ? gate.reasonCode() : "",
                        "message", gate.message() != null ? gate.message() : ""
                ))
        )));
    }

    private PositionSizingResult resolveSizing(StrategySignalEntity signal, UUID userId, ExecutionMode mode) {
        String strategyKey = signal.getStrategyName() != null ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;
        PositionSizingRequest req = new PositionSizingRequest(
                strategyKey,
                userId,
                signal.getId(),
                signal.getSymbol(),
                signal.getSuggestedQty(),
                signal.getEntryReferencePrice(),
                mode,
                Boolean.TRUE.equals(signal.getTestTrade()));
        PositionSizingResult result = positionSizingService.resolve(req);
        if (!result.accepted()) {
            throw new PositionSizingRejectedException(
                    result.rejectedReason() != null ? result.rejectedReason() : "SIZING_REJECTED");
        }
        return result;
    }

    private void recordSizingTelemetry(
            StrategySignalEntity signal, UUID userId, ExecutionMode mode,
            PositionSizingResult sizing, UUID orderId) {
        String strategyKey = signal.getStrategyName() != null ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;
        positionSizingTelemetryService.record(
                new PositionSizingRequest(
                        strategyKey, userId, signal.getId(), signal.getSymbol(),
                        signal.getSuggestedQty(), signal.getEntryReferencePrice(), mode,
                        Boolean.TRUE.equals(signal.getTestTrade())),
                sizing,
                orderId);
    }

    private void dispatchBothMode(StrategySignalEntity signal, UUID userId, String baseIdempotencyKey,
                                   String strategyKey, boolean synchronousExecution, Instant safetyNow) {
        PositionSizingResult sizing;
        try {
            sizing = resolveSizing(signal, userId, ExecutionMode.BOTH);
        } catch (PositionSizingRejectedException ex) {
            log.warn("both_mode.sizing_rejected signalId={} reason={}", signal.getId(), ex.getMessage());
            riskEventRecorder.record(userId, null, "SIZING_REJECTED", "REJECT", ex.getMessage());
            signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), "SIZING_REJECTED");
            return;
        }

        OmsOrder paperDraft = buildDraftFromSignal(signal, ExecutionMode.PAPER, userId, sizing);
        OmsOrder paperOrder = orderLifecycleService.createOrGetIdempotent(
                userId, baseIdempotencyKey + ":PAPER", paperDraft);

        // LIVE leg — run through live gate
        LiveTraderEligibilityResult gate = liveTradingTraderEligibilityService
                .evaluateForLiveOrder(userId, strategyKey, "ZERODHA");
        OmsOrder liveOrder = null;
        if (gate.allowed()) {
            OmsOrder liveDraft = buildDraftFromSignal(signal, ExecutionMode.LIVE, userId, sizing);
            liveOrder = orderLifecycleService.createOrGetIdempotent(
                    userId, baseIdempotencyKey + ":LIVE", liveDraft);
        } else {
            log.warn("both_mode.live_leg.blocked signalId={} reason={}", signal.getId(), gate.reasonCode());
            riskEventRecorder.record(userId, null, gate.reasonCode(), "REJECT", gate.message());
        }

        // Link pairs and record comparison stub
        if (liveOrder != null) {
            paperOrder.setPairedOrderId(liveOrder.getId());
            liveOrder.setPairedOrderId(paperOrder.getId());
            executionComparisonService.recordPairDispatched(
                    signal.getId(), liveOrder.getId(), paperOrder.getId(), strategyKey, signal.getSymbol(),
                    sizing.normalizedQuantity(), sizing.normalizedQuantity());
        }

        if (paperOrder.getState() == OrderState.CREATED) {
            capitalReservationService.reserve(
                    strategyKey, userId, signal.getId(), paperOrder.getId(), signal.getSymbol(), sizing);
        }
        recordSizingTelemetry(signal, userId, ExecutionMode.BOTH, sizing, paperOrder.getId());
        long fillKey = fillDeterminismKey(signal);
        Instant anchor = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : Instant.now();

        // Transition and dispatch PAPER
        if (paperOrder.getState() == OrderState.CREATED) {
            paperOrder = advanceOrderForDispatch(paperOrder, signal, userId, ExecutionMode.PAPER);
            if (paperOrder.getState() == OrderState.PENDING_SUBMISSION) {
                executionService.dispatch(new ExecutionDispatchMessage(
                        paperOrder.getId(), userId, signal.getId(), "SIM", 0,
                        signal.getBacktestRunId(), "PAPER", fillKey, anchor), synchronousExecution);
            }
        }

        // Transition and dispatch LIVE
        if (liveOrder != null && liveOrder.getState() == OrderState.CREATED) {
            liveOrder = advanceOrderForDispatch(liveOrder, signal, userId, ExecutionMode.LIVE);
            if (liveOrder.getState() == OrderState.PENDING_SUBMISSION) {
                executionService.dispatch(new ExecutionDispatchMessage(
                        liveOrder.getId(), userId, signal.getId(), "ZERODHA", 0,
                        signal.getBacktestRunId(), "LIVE", fillKey, anchor), synchronousExecution);
            }
        }

        log.info("both_mode.dispatched signalId={} paper={} live={}",
                signal.getId(), paperOrder.getId(), liveOrder != null ? liveOrder.getId() : "BLOCKED");
    }

    private OmsOrder advanceOrderForDispatch(
            OmsOrder order, StrategySignalEntity signal, UUID userId, ExecutionMode legMode) {
        if (order.getState() != OrderState.CREATED) {
            return order;
        }
        order = orderLifecycleService.transition(order.getId(), OrderState.VALIDATED, null);
        order = orderLifecycleService.transition(order.getId(), OrderState.RISK_CHECK, null);

        ZoneId zone = ZoneId.of(riskZone);
        Instant evalInstant = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : Instant.now();
        RiskContext ctx = riskContextFactory.build(userId, order, zone, evalInstant, atrToCloseRatio(signal));
        RiskDecision decision = riskEngineService.evaluate(ctx);
        riskEvaluationTraceService.record(ctx, decision, order.getId());
        if (!decision.allowed()) {
            capitalReservationService.releaseByOrder(order.getId(), "RISK_REJECT");
            riskEventRecorder.record(
                    userId,
                    order.getId(),
                    decision.reasonCode() != null ? decision.reasonCode() : "RISK",
                    "REJECT",
                    decision.message()
            );
            order = orderLifecycleService.transition(order.getId(), OrderState.REJECTED, decision.message());
            executionTraceService.trace(order, ExecutionEventType.EXECUTION_REJECTED, Map.of(
                    "phase", "RISK",
                    "reason", decision.message() != null ? decision.message() : ""
            ));
            signalDistributionTelemetryService.recordRiskRejected(userId, signal.getId());
            return order;
        }

        executionTraceService.trace(order, ExecutionEventType.RISK_CHECK_PASSED, Map.of(
                "riskReason", decision.message() != null ? decision.message() : "OK"
        ));
        order = orderLifecycleService.transition(order.getId(), OrderState.PENDING_SUBMISSION, null);

        if (legMode == ExecutionMode.LIVE) {
            brokerPositionTruthService.syncUser(userId);
            String side = order.getSide();
            ExecutionGuardMode guardMode = "SELL".equalsIgnoreCase(side)
                    ? ExecutionGuardMode.EXIT_SAFE
                    : ExecutionGuardMode.ENTRY_STRICT;
            var brokerViolations = brokerPositionTruthService.validateForExecution(
                    userId, order.getSymbol(), side, guardMode, Instant.now());
            if (!brokerViolations.isEmpty()) {
                ExecutionGuardViolation v = brokerViolations.getFirst();
                order = orderLifecycleService.transition(order.getId(), OrderState.REJECTED, v.message());
                executionTraceService.trace(order, ExecutionEventType.EXECUTION_REJECTED, Map.of(
                        "phase", "BROKER_TRUTH",
                        "code", v.code(),
                        "detail", v.detail() != null ? v.detail() : ""
                ));
                signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), v.code());
            }
        }
        return order;
    }
}
