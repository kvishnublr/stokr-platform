package com.stokr.execution.safety;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OmsSafetyGateService {

    private final TradingKillSwitchService killSwitchService;
    private final LiveRolloutGateService liveRolloutGateService;
    private final LiveSignalStaleGuardService staleSignalGuardService;
    private final OmsExecutionDedupeService dedupeService;
    private final OmsExposureControlService exposureControlService;
    private final MarketCloseProtectionService marketCloseProtectionService;
    private final BrokerDisconnectProtectionService brokerDisconnectProtectionService;
    private final OmsSafetyBlockedOrderRepository blockedOrderRepository;

    public OmsSafetyGateResult evaluatePreOrder(
            StrategySignalEntity signal,
            UUID userId,
            ExecutionMode requestedMode,
            Instant now) {
        String strategyKey = signal.getStrategyName() != null
                ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;
        ExecutionMode effectiveMode = requestedMode;
        List<String> reasons = new ArrayList<>();

        if (killSwitchService.forcesPaperMode() && effectiveMode == ExecutionMode.LIVE) {
            effectiveMode = ExecutionMode.PAPER;
            reasons.add("KILL_SWITCH_FORCE_PAPER");
        }

        LiveRolloutGateService.LiveRolloutDecision rollout =
                liveRolloutGateService.evaluate(strategyKey, userId, signal, effectiveMode, now);
        if (effectiveMode == ExecutionMode.LIVE && !rollout.liveAllowed()) {
            effectiveMode = rollout.effectiveMode();
            reasons.addAll(rollout.blockers());
        }

        if (effectiveMode == ExecutionMode.LIVE) {
            if (marketCloseProtectionService.blocksNewLiveEntries(now) && isEntrySignal(signal)) {
                return block(signal, userId, requestedMode, ExecutionMode.PAPER,
                        "MARKET_CLOSE_NO_NEW_ENTRIES", "New LIVE entries blocked near market close", reasons);
            }
            if (brokerDisconnectProtectionService.blocksLiveOrders(userId)) {
                return block(signal, userId, requestedMode, ExecutionMode.PAPER,
                        "BROKER_DISCONNECTED", "Broker disconnected — LIVE blocked", reasons);
            }
            Instant signalTime = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : signal.getCreatedAt();
            var stale = staleSignalGuardService.check(strategyKey, signalTime, now);
            if (stale.isPresent()) {
                return block(signal, userId, requestedMode, ExecutionMode.PAPER,
                        stale.get().code(), stale.get().message(), reasons);
            }
        }

        return OmsSafetyGateResult.pass(effectiveMode, reasons);
    }

    @Transactional
    public boolean acquireLiveDedupe(
            StrategySignalEntity signal,
            UUID userId,
            UUID orderId,
            ExecutionMode mode,
            Instant now) {
        if (mode != ExecutionMode.LIVE) {
            return true;
        }
        String direction = mapDirection(signal.getSignalType());
        boolean acquired = dedupeService.tryAcquire(
                signal.getStrategyName(), signal.getSymbol(), direction, userId, orderId, now);
        if (!acquired) {
            recordBlocked(signal, userId, ExecutionMode.LIVE.name(), ExecutionMode.LIVE.name(),
                    "DUPLICATE_EXECUTION_KEY", "Duplicate LIVE execution key within dedupe window");
        }
        return acquired;
    }

    @Transactional
    public OmsSafetyGateResult evaluateExposure(OmsOrder draft, UUID userId, Instant now) {
        var violation = exposureControlService.evaluateLive(userId, draft, now);
        if (violation.isPresent()) {
            recordBlocked(null, userId, ExecutionMode.LIVE.name(), ExecutionMode.PAPER.name(),
                    violation.get().code(), violation.get().message());
            return OmsSafetyGateResult.block(ExecutionMode.PAPER, violation.get().code(), violation.get().message());
        }
        return OmsSafetyGateResult.pass(draft.getExecutionMode(), List.of());
    }

    private OmsSafetyGateResult block(
            StrategySignalEntity signal,
            UUID userId,
            ExecutionMode requested,
            ExecutionMode effective,
            String code,
            String message,
            List<String> reasons) {
        recordBlocked(signal, userId, requested.name(), effective.name(), code, message);
        List<String> all = new ArrayList<>(reasons);
        all.add(code);
        log.warn("oms.safety.blocked code={} message={}", code, message);
        return OmsSafetyGateResult.block(effective, code, message, all);
    }

    private void recordBlocked(
            StrategySignalEntity signal,
            UUID userId,
            String requested,
            String effective,
            String code,
            String message) {
        OmsSafetyBlockedOrder row = new OmsSafetyBlockedOrder();
        row.setUserId(userId);
        if (signal != null) {
            row.setSignalId(signal.getId());
            row.setStrategyName(signal.getStrategyName());
            row.setSymbol(signal.getSymbol());
        }
        row.setRequestedMode(requested);
        row.setEffectiveMode(effective);
        row.setBlockCode(code);
        row.setBlockMessage(message);
        row.setCreatedAt(Instant.now());
        blockedOrderRepository.save(row);
    }

    private static boolean isEntrySignal(StrategySignalEntity signal) {
        return signal.getSignalType() == SignalType.BUY || signal.getSignalType() == SignalType.SELL;
    }

    private static String mapDirection(SignalType type) {
        if (type == null) {
            return "UNKNOWN";
        }
        return switch (type) {
            case BUY -> "BUY";
            case SELL, EXIT -> "SELL";
            case HOLD -> "HOLD";
        };
    }

    public record OmsSafetyGateResult(
            boolean blocked,
            ExecutionMode effectiveMode,
            String blockCode,
            String blockMessage,
            List<String> reasons) {

        static OmsSafetyGateResult pass(ExecutionMode mode, List<String> reasons) {
            return new OmsSafetyGateResult(false, mode, null, null, reasons);
        }

        static OmsSafetyGateResult block(ExecutionMode mode, String code, String message) {
            return new OmsSafetyGateResult(true, mode, code, message, List.of(code));
        }

        static OmsSafetyGateResult block(
                ExecutionMode mode,
                String code,
                String message,
                List<String> reasons) {
            return new OmsSafetyGateResult(true, mode, code, message, reasons);
        }
    }
}
