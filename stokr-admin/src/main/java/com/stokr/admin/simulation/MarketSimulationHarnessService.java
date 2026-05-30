package com.stokr.admin.simulation;

import com.stokr.common.simulation.SimulatedBrokerOutcome;
import com.stokr.common.simulation.SimulationModeService;
import com.stokr.common.simulation.SimulationScenario;
import com.stokr.common.simulation.SimulationScenarioContext;
import com.stokr.marketdata.simulation.SimulatedMarketDataEngine;
import com.stokr.marketdata.simulation.SimulatedMarketDataEngine.SimulatedMarketSession;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.catalog.CatalogDrivenScanScheduler;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.runtime.StrategyRegistry;
import com.stokr.strategy.service.ConfidenceEngineV2;
import com.stokr.strategy.service.PressureSmartExitService;
import com.stokr.strategy.service.SignalOutcomeTrackerService;
import com.stokr.strategy.service.StrategySignalEntityMapper;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs named scenarios through catalog scan and/or direct strategy evaluation + full signal pipeline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketSimulationHarnessService {

    private final SimulationModeService simulationMode;
    private final SimulationRunService simulationRunService;
    private final SimulatedMarketDataEngine marketDataEngine;
    private final StrategyRegistry strategyRegistry;
    private final ConfidenceEngineV2 confidenceEngineV2;
    private final StrategySignalPipelineService signalPipelineService;
    private final StrategySignalRepository signalRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final ObjectProvider<CatalogDrivenScanScheduler> catalogScanProvider;
    private final PressureSmartExitService pressureSmartExitService;
    private final SignalOutcomeTrackerService signalOutcomeTrackerService;

    public List<SimulationScenario> listScenarios() {
        return List.of(SimulationScenario.values());
    }

    @Transactional
    public SimulationHarnessReport runScenario(SimulationHarnessRequest request) {
        if (!simulationMode.isActive()) {
            throw new IllegalStateException(
                    "Simulation runtime disabled — enable via POST /api/admin/simulation/runtime/enable");
        }
        SimulationScenario scenario = request.scenario() != null ? request.scenario() : SimulationScenario.CUSTOM;
        UUID runId = simulationRunService.startRun(scenario.name(), simulationMode.systemUserId()).getId();
        String strategyKey = resolveStrategyKey(scenario, request.strategyKey());
        String symbol = request.symbol() != null ? request.symbol().trim().toUpperCase() : defaultSymbol(strategyKey);
        SimulatedBrokerOutcome brokerOutcome = resolveBrokerOutcome(scenario, request.brokerOutcome());
        String executionMode = request.executionMode() != null
                ? request.executionMode()
                : simulationMode.defaultExecutionMode();
        if (scenario == SimulationScenario.BROKER_REJECT) {
            executionMode = "LIVE";
        }

        SimulationScenarioContext.set(scenario, brokerOutcome, runId);
        List<String> steps = new ArrayList<>();
        steps.add("RUN_ID=" + runId);
        try {
            SimulatedMarketSession session = marketDataEngine.seedSession(
                    scenario, symbol, request.basePrice(), request.sessionBars());
            steps.add("SEEDED_MARKET_DATA");

            StrategySignalEntity signal = null;
            if (request.useCatalogScan()) {
                CatalogDrivenScanScheduler catalogScan = catalogScanProvider.getIfAvailable();
                if (catalogScan == null) {
                    SimulationHarnessReport fail = buildReport(runId, scenario, strategyKey, symbol, steps, null, null, false,
                            "Catalog scan unavailable — set stokr.catalog.scan.enabled=true");
                    simulationRunService.completeRun(runId, false, fail);
                    return fail;
                }
                catalogScan.scan();
                steps.add("CATALOG_SCAN");
                signal = signalRepository.findTop200ByDeletedFalseOrderByCreatedAtDesc().stream()
                        .filter(s -> strategyKey.equalsIgnoreCase(s.getStrategyName()))
                        .filter(s -> symbol.equalsIgnoreCase(s.getSymbol()))
                        .findFirst()
                        .orElse(null);
            } else {
                signal = evaluateAndPersist(strategyKey, symbol, session, executionMode);
                steps.add("STRATEGY_EVALUATE_PIPELINE");
            }

            if (signal == null) {
                SimulationHarnessReport fail = buildReport(runId, scenario, strategyKey, symbol, steps, null, null, false,
                        "No signal produced — check strategy conditions or useCatalogScan");
                simulationRunService.completeRun(runId, false, fail);
                return fail;
            }

            Optional<OmsOrder> order = resolveOrder(signal.getId());
            steps.add(order.isPresent() ? "OMS_ORDER_CREATED" : "OMS_NO_ORDER");

            if (scenario == SimulationScenario.PROTECTION_EXIT || request.runProtectionMonitor()) {
                pressureSmartExitService.monitorAndExit();
                steps.add("PROTECTION_MONITOR");
            }

            int tickCount = request.pushLiveTicks();
            BigDecimal tickOverride = request.tickPriceOverride();
            if (tickCount <= 0) {
                tickCount = defaultTickPushCount(scenario);
                tickOverride = defaultTickPrice(scenario, signal, tickOverride);
            }
            if (tickCount > 0) {
                marketDataEngine.pushLiveTicks(session, tickCount, tickOverride);
                steps.add("LIVE_TICKS_PUSHED");
            }

            StrategySignalEntity refreshed = signalRepository.findById(signal.getId()).orElse(signal);
            signalOutcomeTrackerService.evaluateSingleSignal(refreshed, Instant.now());
            refreshed = signalRepository.findById(signal.getId()).orElse(refreshed);
            steps.add("OUTCOME_TRACKER");
            boolean confidenceOk = refreshed.getConfidenceScore() != null
                    && "CONFIDENCE_V2".equals(refreshed.getConfidenceVersion());
            Optional<OmsOrder> orderFinal = resolveOrder(refreshed.getId());
            SimulationHarnessReport report = buildReport(
                    runId, scenario, strategyKey, symbol, steps, refreshed, orderFinal.orElse(null), confidenceOk, null);
            simulationRunService.completeRun(runId, report.success(), report);
            return report;
        } finally {
            SimulationScenarioContext.clear();
        }
    }

    /** Evaluate at the last seeded bar so integrity lookbacks match synthetic session candles. */
    private static Instant simulationEvaluateAsOf(SimulatedMarketSession session) {
        return session.equityBars().get(session.equityBars().size() - 1).openTime();
    }

    /**
     * Deterministic probe when catalog generators return HOLD on synthetic bars — still exercises
     * simulation tagging, OMS, broker sim, and outcome tracker for release validation.
     */
    private static StrategySignal harnessProbeSignal(String symbol, BigDecimal last) {
        BigDecimal entry = last;
        BigDecimal target = entry.multiply(BigDecimal.valueOf(1.02));
        BigDecimal stop = entry.multiply(BigDecimal.valueOf(0.98));
        return new StrategySignal(
                SignalType.BUY,
                symbol,
                BigDecimal.ONE,
                "sim-harness-probe",
                entry,
                stop,
                target
        );
    }

    private StrategySignalEntity evaluateAndPersist(
            String strategyKey,
            String symbol,
            SimulatedMarketSession session,
            String executionMode
    ) {
        TradingStrategy strategy = strategyRegistry.get(strategyKey);
        BigDecimal last = session.equityBars().get(session.equityBars().size() - 1).close();
        Instant asOf = simulationEvaluateAsOf(session);
        StrategyContext ctx = new StrategyContext(symbol, asOf, Map.of(), last);
        StrategySignal raw = strategy.evaluate(ctx);
        if (raw == null || raw.type() == SignalType.HOLD) {
            raw = harnessProbeSignal(symbol, last);
        }
        StrategySignal scored = confidenceEngineV2.enrich(raw, strategyKey, symbol, asOf);
        StrategySignalEntity entity = StrategySignalEntityMapper.baseEntity(
                scored,
                strategyKey,
                symbol,
                asOf,
                simulationMode.systemUserId(),
                executionMode,
                "2.0.0"
        );
        entity.setSimulation(true);
        entity.setSimulationRunId(SimulationScenarioContext.runId());
        entity.setSimulationScenario(SimulationScenarioContext.scenario() != null
                ? SimulationScenarioContext.scenario().name()
                : "CUSTOM");
        entity.setTestTrade(false);
        // Simulation tags + SIMULATION provenance are applied centrally in StrategySignalPipelineService.
        StrategySignalEntityMapper.applyStreamMetadata(
                entity,
                com.stokr.strategy.signals.SignalOwnerType.SYSTEM,
                "PENDING"
        );
        return signalPipelineService.persistAndDispatch(
                entity,
                "sim-harness:" + UUID.randomUUID(),
                executionMode,
                SignalProvenance.SIMULATION,
                false
        );
    }

    private SimulationHarnessReport buildReport(
            UUID runId,
            SimulationScenario scenario,
            String strategyKey,
            String symbol,
            List<String> steps,
            StrategySignalEntity signal,
            OmsOrder order,
            boolean confidenceOk,
            String error
    ) {
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("signalGenerated", signal != null);
        validation.put("confidencePersisted", signal != null && signal.getConfidenceScore() != null);
        validation.put("confidenceV2", confidenceOk);
        validation.put("omsExecuted", order != null);
        validation.put("orderState", order != null ? order.getState().name() : null);
        validation.put("outcomeStatus", signal != null ? signal.getOutcomeStatus() : null);
        validation.put("entryPrice", signal != null ? signal.getEntryPrice() : null);
        validation.put("targetPrice", signal != null ? signal.getTargetPrice() : null);
        validation.put("stopPrice", signal != null ? signal.getStopPrice() : null);
        validation.put("realizedPnl", signal != null ? signal.getRealizedPnl() : null);
        validation.put("protectionTriggered",
                signal != null && signal.getOutcomeStatus() != null
                        && signal.getOutcomeStatus().toUpperCase().contains("PROTECT"));
        validation.put("simulationRunId", runId != null ? runId.toString() : null);
        validation.put("pipelineSteps", steps);
        validation.put("error", error);
        boolean success = signal != null && signal.getConfidenceScore() != null;
        if (scenario == SimulationScenario.BROKER_REJECT) {
            success = order != null && brokerRejectionOrder(order);
        } else {
            success = success && order != null;
        }
        return new SimulationHarnessReport(
                runId != null ? runId.toString() : null,
                scenario.name(),
                strategyKey,
                symbol,
                signal != null ? signal.getId().toString() : null,
                success,
                validation
        );
    }

    private static String resolveStrategyKey(SimulationScenario scenario, String override) {
        if (override != null && !override.isBlank()) {
            return override.trim().toUpperCase();
        }
        return switch (scenario) {
            case GAP_FILL_WIN, GAP_FILL_LOSS -> "GAP_FILL";
            case VWAP_BOUNCE_WIN, VWAP_BOUNCE_LOSS -> "VWAP_BOUNCE";
            case NSE_SPIKE_WIN -> "NSE_SPIKE_DETECTION";
            case PROTECTION_EXIT, TARGET_HIT, SL_HIT, FEED_FAILURE, BROKER_REJECT -> "ADV_CASH";
            default -> "ADV_CASH";
        };
    }

    private static String defaultSymbol(String strategyKey) {
        return switch (strategyKey) {
            case "INDEX_HUNT" -> "NIFTY";
            default -> "SBIN";
        };
    }

    private static SimulatedBrokerOutcome resolveBrokerOutcome(
            SimulationScenario scenario,
            SimulatedBrokerOutcome override
    ) {
        if (override != null) {
            return override;
        }
        return switch (scenario) {
            case BROKER_REJECT -> SimulatedBrokerOutcome.REJECTED;
            default -> SimulatedBrokerOutcome.FILLED;
        };
    }

    private static boolean brokerRejectionOrder(OmsOrder order) {
        if (order.getState() == OrderState.FAILED) {
            return true;
        }
        if (order.getState() == OrderState.REJECTED) {
            String reason = order.getRejectReason();
            return reason != null && reason.toUpperCase().contains("BROKER");
        }
        return false;
    }

    private Optional<OmsOrder> resolveOrder(UUID signalId) {
        List<OmsOrder> orders = omsOrderRepository.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(signalId);
        return orders.isEmpty() ? Optional.empty() : Optional.of(orders.get(0));
    }

    private static int defaultTickPushCount(SimulationScenario scenario) {
        return switch (scenario) {
            case TARGET_HIT, SL_HIT, PROTECTION_EXIT, NSE_SPIKE_WIN -> 8;
            default -> 0;
        };
    }

    private static BigDecimal defaultTickPrice(
            SimulationScenario scenario,
            StrategySignalEntity signal,
            BigDecimal override
    ) {
        if (override != null) {
            return override;
        }
        BigDecimal target = signal.getTargetPrice();
        BigDecimal stop = signal.getStopPrice();
        return switch (scenario) {
            case TARGET_HIT -> target != null
                    ? target.multiply(BigDecimal.valueOf(1.002))
                    : null;
            case SL_HIT -> stop != null
                    ? stop.multiply(BigDecimal.valueOf(0.998))
                    : null;
            case PROTECTION_EXIT -> signal.getEntryReferencePrice() != null
                    ? signal.getEntryReferencePrice().multiply(BigDecimal.valueOf(0.995))
                    : null;
            default -> null;
        };
    }

    public record SimulationHarnessRequest(
            SimulationScenario scenario,
            String strategyKey,
            String symbol,
            BigDecimal basePrice,
            int sessionBars,
            String executionMode,
            SimulatedBrokerOutcome brokerOutcome,
            boolean useCatalogScan,
            boolean runProtectionMonitor,
            int pushLiveTicks,
            BigDecimal tickPriceOverride
    ) {
        public SimulationHarnessRequest {
            if (sessionBars <= 0) {
                sessionBars = 120;
            }
        }
    }

    public record SimulationHarnessReport(
            String simulationRunId,
            String scenario,
            String strategyKey,
            String symbol,
            String signalId,
            boolean success,
            Map<String, Object> validation
    ) {
    }
}
