package com.stokr.execution.safety;

import com.stokr.marketdata.monitor.FeedHealthMonitorService;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.operational.StrategyExecutionMode;
import com.stokr.strategy.operational.StrategyExecutionModeService;
import com.stokr.strategy.operational.TradingSafeStartupGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LiveRolloutGateService {

    private final StrategyExecutionModeService executionModeService;
    private final FeedHealthMonitorService feedHealthMonitorService;
    private final TradingSafeStartupGateService safeStartupGateService;
    private final TradingKillSwitchService killSwitchService;
    private final BrokerDisconnectProtectionService brokerDisconnectProtectionService;
    private final OmsExposureControlService exposureControlService;

    public LiveRolloutDecision evaluate(
            String strategyKey,
            UUID userId,
            StrategySignalEntity signal,
            ExecutionMode requestedMode,
            Instant now) {
        if (requestedMode != ExecutionMode.LIVE) {
            return LiveRolloutDecision.allow(requestedMode, List.of());
        }

        List<String> blockers = new ArrayList<>();

        // Test Signal Lab explicit LIVE broker E2E — bypass feed/startup/strategy-mode downgrades.
        // Kill switch and broker connectivity still apply.
        if (Boolean.TRUE.equals(signal.getTestTrade())) {
            if (killSwitchService.isActive()) {
                blockers.add("KILL_SWITCH_ACTIVE");
            }
            if (brokerDisconnectProtectionService.blocksLiveOrders(userId)) {
                blockers.add("BROKER_DISCONNECTED_OR_DEGRADED");
            }
            if (!blockers.isEmpty()) {
                log.warn("oms.live_rollout.test_lab_blocked strategy={} blockers={}", strategyKey, blockers);
                return LiveRolloutDecision.downgrade(ExecutionMode.PAPER, blockers);
            }
            return LiveRolloutDecision.allow(ExecutionMode.LIVE, List.of("TEST_LAB_LIVE_E2E"));
        }

        StrategyExecutionMode configured = executionModeService.modeFor(strategyKey);
        if (configured != StrategyExecutionMode.LIVE) {
            blockers.add("EXECUTION_MODE_NOT_LIVE:" + configured.name());
        }

        if (killSwitchService.isActive()) {
            blockers.add("KILL_SWITCH_ACTIVE");
        }

        if (!safeStartupGateService.isTradingReady(now)) {
            blockers.add("SAFE_STARTUP_NOT_READY:" + safeStartupGateService.snapshot(now).get("blockReason"));
        }

        FeedHealthMonitorService.FeedHealthSnapshot feed = feedHealthMonitorService.snapshot(now);
        if (feed.equityStale() || feed.indexStale() || feed.level() == FeedHealthMonitorService.FeedHealthLevel.ERROR) {
            blockers.add("FEED_UNHEALTHY");
        }

        if (brokerDisconnectProtectionService.blocksLiveOrders(userId)) {
            blockers.add("BROKER_DISCONNECTED_OR_DEGRADED");
        }

        var draft = new com.stokr.oms.domain.OmsOrder();
        draft.setExecutionMode(ExecutionMode.LIVE);
        draft.setStrategyKey(strategyKey);
        draft.setSymbol(signal.getSymbol());
        draft.setQuantity(signal.getSuggestedQty());
        draft.setEntryReferencePrice(signal.getEntryReferencePrice());
        exposureControlService.evaluateLive(userId, draft, now).ifPresent(v -> blockers.add(v.code()));

        if (!blockers.isEmpty()) {
            log.warn("oms.live_rollout.downgrade strategy={} blockers={}", strategyKey, blockers);
            return LiveRolloutDecision.downgrade(ExecutionMode.PAPER, blockers);
        }

        return LiveRolloutDecision.allow(ExecutionMode.LIVE, List.of());
    }

    public record LiveRolloutDecision(
            ExecutionMode effectiveMode,
            boolean liveAllowed,
            List<String> blockers) {

        static LiveRolloutDecision allow(ExecutionMode mode, List<String> blockers) {
            return new LiveRolloutDecision(mode, mode == ExecutionMode.LIVE, blockers);
        }

        static LiveRolloutDecision downgrade(ExecutionMode mode, List<String> blockers) {
            return new LiveRolloutDecision(mode, false, blockers);
        }
    }
}
