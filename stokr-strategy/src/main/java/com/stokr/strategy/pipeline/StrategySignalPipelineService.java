package com.stokr.strategy.pipeline;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.events.SignalPublishedEvent;
import com.stokr.common.pipeline.OmsIntentDispatcher;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.common.runtime.ExecutionPipelineRuntimeReadinessService;
import com.stokr.common.telemetry.SignalDistributionTelemetryService;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.service.SignalEmissionGuardService;
import com.stokr.strategy.service.SignalPriceEnrichmentService;
import com.stokr.strategy.service.SignalQualityGateService;
import com.stokr.strategy.service.SignalProvenanceResolver;
import com.stokr.strategy.service.SignalSymbolPriceGateService;
import com.stokr.strategy.service.StrategyDailySignalCapService;
import com.stokr.strategy.signals.SignalProvenance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategySignalPipelineService {

    private final StrategySignalRepository signalRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final RabbitTemplate rabbitTemplate;
    private final OmsIntentDispatcher omsIntentDispatcher;
    private final ApplicationEventPublisher eventPublisher;
    private final SignalDistributionTelemetryService signalDistributionTelemetryService;
    private final ExecutionPipelineRuntimeReadinessService executionPipelineRuntimeReadinessService;
    private final SignalPriceEnrichmentService signalPriceEnrichmentService;
    private final SignalEmissionGuardService signalEmissionGuardService;
    private final SignalProvenanceResolver signalProvenanceResolver;
    private final SignalSymbolPriceGateService signalSymbolPriceGateService;
    private final SignalQualityGateService signalQualityGateService;
    private final StrategyDailySignalCapService dailySignalCapService;

    @Value("${stokr.strategy.signal-session-guard.enabled:true}")
    private boolean signalSessionGuardEnabled;

    @Value("${stokr.strategy.replay.dispatch-to-oms:false}")
    private boolean replayDispatchToOms;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId marketZone;

    @Value("${stokr.marketdata.session.nse.start:09:15}")
    private LocalTime nseStart;

    @Value("${stokr.marketdata.session.nse.end:15:30}")
    private LocalTime nseEnd;

    @Value("${stokr.marketdata.session.mcx.start:09:00}")
    private LocalTime mcxStart;

    @Value("${stokr.marketdata.session.mcx.end:23:30}")
    private LocalTime mcxEnd;

    private static final List<String> MCX_PREFIXES = List.of(
            "CRUDEOIL", "NATURALGAS", "GOLD", "GOLDM", "GOLDPETAL", "SILVER", "SILVERM",
            "SILVERMIC", "COPPER", "ALUMINIUM", "ALUMINUM", "ZINC", "LEAD", "NICKEL",
            "MENTHAOIL", "COTTON", "CARDAMOM"
    );

    @Transactional
    public StrategySignalEntity persistAndDispatch(StrategySignalEntity signal, String correlationId, String executionMode) {
        return persistAndDispatch(signal, correlationId, executionMode, false);
    }

    @Transactional
    public StrategySignalEntity persistAndDispatch(
            StrategySignalEntity signal,
            String correlationId,
            String executionMode,
            boolean skipBrokerExecution) {
        return persistAndDispatch(signal, correlationId, executionMode, null, skipBrokerExecution);
    }

    @Transactional
    public StrategySignalEntity persistAndDispatch(
            StrategySignalEntity signal,
            String correlationId,
            String executionMode,
            SignalProvenance provenanceOverride) {
        return persistAndDispatch(signal, correlationId, executionMode, provenanceOverride, false);
    }

    @Transactional
    public StrategySignalEntity persistAndDispatch(
            StrategySignalEntity signal,
            String correlationId,
            String executionMode,
            SignalProvenance provenanceOverride,
            boolean skipBrokerExecution) {
        boolean replayAnalytics = provenanceOverride == SignalProvenance.REPLAY;
        if (!replayAnalytics && !skipBrokerExecution
                && !executionPipelineRuntimeReadinessService.canRouteExecutionMode(executionMode)) {
            throw new IllegalStateException(
                    "Execution pipeline disabled: Rabbit listeners are OFF. Signal routing and OMS execution are inactive."
            );
        }
        signalProvenanceResolver.applyForPersist(signal, executionMode, provenanceOverride);
        boolean replaySignal = signal.getSignalSource() == SignalProvenance.REPLAY || replayAnalytics;

        Instant signalTime = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : Instant.now();
        Instant sessionCheckTime = replaySignal ? signalTime : Instant.now();
        if (signalSessionGuardEnabled && !Boolean.TRUE.equals(signal.getTestTrade()) && shouldDropOutsideSession(signal, sessionCheckTime)) {
            log.info("signal.dropped_outside_session strategy={} symbol={} mode={} replay={}",
                    signal.getStrategyName(), signal.getSymbol(), executionMode, replaySignal);
            return null;
        }
        if (!Boolean.TRUE.equals(signal.getTestTrade()) && signal.getBacktestRunId() == null
                && signal.getSignalSource() != null && signal.getSignalSource().isProductionAnalytics()) {
            signalPriceEnrichmentService.enrichIfMissing(signal, signalTime);
            if (signalSymbolPriceGateService.exceedsMaxPrice(signal, signalTime)) {
                return null;
            }
            if (signalQualityGateService.shouldDrop(signal)) {
                return null;
            }
            if (signalEmissionGuardService.shouldSuppress(signal)) {
                log.info("signal.dropped_duplicate strategy={} symbol={} type={}",
                        signal.getStrategyName(), signal.getSymbol(), signal.getSignalType());
                return null;
            }
            if (signal.getOutcomeStatus() == null || signal.getOutcomeStatus().isBlank()) {
                signal.setOutcomeStatus("PENDING");
            }
            String capKey = signal.getStrategyName() != null ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;
            if (dailySignalCapService.isOverCap(capKey, signalTime)) {
                log.info("signal.dropped_daily_cap strategy={} symbol={}", capKey, signal.getSymbol());
                return null;
            }
        }
        if (replaySignal && (signal.getOutcomeStatus() == null || signal.getOutcomeStatus().isBlank())) {
            signal.setOutcomeStatus("PENDING");
        }
        StrategySignalEntity saved = signalRepository.save(signal);

        String cid = (correlationId == null || correlationId.isBlank()) ? UUID.randomUUID().toString() : correlationId;
        String sk = saved.getStrategyName() != null ? saved.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;

        // Resolve RUNNING trader instances WITHIN transaction so they're consistent with the saved signal
        List<StrategyInstance> runningInstances = strategyInstanceRepository.findAllRunningByStrategyKey(sk);

        String omsExecutionMode = saved.getSignalSource() == SignalProvenance.REPLAY
                ? "SIMULATED"
                : executionMode;
        SignalPersistedMessage systemMsg = new SignalPersistedMessage(
                saved.getId(),
                saved.getUserId(),
                cid,
                saved.getBacktestRunId(),
                omsExecutionMode
        );

        long dispatchLatencyStartNanos = System.nanoTime();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // strategy.signal.queue — telemetry/broadcast (one message per signal, always system user)
                rabbitTemplate.convertAndSend(PipelineQueues.STRATEGY_SIGNAL, systemMsg);

                // oms.order.queue — fan out to every RUNNING trader instance for this strategy
                // REPLAY signals are analytics-only unless explicitly enabled (never LIVE).
                if (saved.getSignalSource() == SignalProvenance.REPLAY && !replayDispatchToOms) {
                    log.info("signal.replay.skip_oms signalId={} strategy={} symbol={}",
                            saved.getId(), sk, saved.getSymbol());
                    eventPublisher.publishEvent(new OperationalRealtimeEvent("signal_routed", java.util.Map.of(
                            "signalId", saved.getId().toString(),
                            "userId", saved.getUserId().toString(),
                            "strategyKey", sk,
                            "executionMode", "REPLAY_ANALYTICS_ONLY"
                    )));
                    eventPublisher.publishEvent(new SignalPublishedEvent(saved.getId(), saved.getUserId(), saved.getSymbol(), sk));
                    signalDistributionTelemetryService.recordPipelineDispatchNanos(System.nanoTime() - dispatchLatencyStartNanos);
                    return;
                }
                if (skipBrokerExecution) {
                    log.info("signal.skip_broker signalId={} strategy={} symbol={} mode={}",
                            saved.getId(), sk, saved.getSymbol(), executionMode);
                    eventPublisher.publishEvent(new OperationalRealtimeEvent("signal_routed", java.util.Map.of(
                            "signalId", saved.getId().toString(),
                            "userId", saved.getUserId().toString(),
                            "strategyKey", sk,
                            "executionMode", executionMode != null ? executionMode : "PAPER"
                    )));
                    eventPublisher.publishEvent(new SignalPublishedEvent(saved.getId(), saved.getUserId(), saved.getSymbol(), sk));
                    signalDistributionTelemetryService.recordPipelineDispatchNanos(System.nanoTime() - dispatchLatencyStartNanos);
                    return;
                }
                if (runningInstances.isEmpty()) {
                    omsIntentDispatcher.dispatch(systemMsg, true);
                    log.info("signal.fanout.no_traders signalId={} strategy={} symbol={}", saved.getId(), sk, saved.getSymbol());
                } else {
                    for (StrategyInstance inst : runningInstances) {
                        String instMode = saved.getSignalSource() == SignalProvenance.REPLAY
                                ? "SIMULATED"
                                : inst.getExecutionMode();
                        SignalPersistedMessage traderMsg = new SignalPersistedMessage(
                                saved.getId(),
                                inst.getUserId(),
                                cid + ":" + inst.getUserId(),
                                saved.getBacktestRunId(),
                                instMode
                        );
                        omsIntentDispatcher.dispatch(traderMsg, true);
                    }
                    log.info("signal.fanout.dispatched signalId={} strategy={} symbol={} traders={}",
                            saved.getId(), sk, saved.getSymbol(), runningInstances.size());
                }

                eventPublisher.publishEvent(new OperationalRealtimeEvent("signal_routed", java.util.Map.of(
                        "signalId", saved.getId().toString(),
                        "userId", saved.getUserId().toString(),
                        "strategyKey", sk,
                        "executionMode", executionMode != null ? executionMode : ""
                )));
                eventPublisher.publishEvent(new SignalPublishedEvent(saved.getId(), saved.getUserId(), saved.getSymbol(), sk));
                signalDistributionTelemetryService.recordPipelineDispatchNanos(System.nanoTime() - dispatchLatencyStartNanos);
            }
        });

        return saved;
    }

    private boolean shouldDropOutsideSession(StrategySignalEntity signal, Instant now) {
        ZonedDateTime zdt = now.atZone(marketZone);
        DayOfWeek dow = zdt.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return true;
        }
        String symbol = signal.getSymbol() == null ? "" : signal.getSymbol().trim().toUpperCase();
        String strategy = signal.getStrategyName() == null ? "" : signal.getStrategyName().trim().toUpperCase();
        boolean mcx = isMcxSignal(strategy, symbol);
        LocalTime t = zdt.toLocalTime();
        LocalTime start = mcx ? mcxStart : nseStart;
        LocalTime end = mcx ? mcxEnd : nseEnd;
        return t.isBefore(start) || t.isAfter(end);
    }

    private boolean isMcxSignal(String strategyName, String symbol) {
        if (strategyName.contains("MCX") || strategyName.contains("COMMODITIES")) {
            return true;
        }
        if (symbol.startsWith("MCX")) {
            return true;
        }
        for (String p : MCX_PREFIXES) {
            if (symbol.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
