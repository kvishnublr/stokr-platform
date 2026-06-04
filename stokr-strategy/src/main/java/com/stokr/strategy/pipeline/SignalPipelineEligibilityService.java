package com.stokr.strategy.pipeline;

import com.stokr.common.market.NseMarketSession;
import com.stokr.marketdata.monitor.FeedHealthMonitorService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.lifecycle.StrategySessionEntryGuardService;
import com.stokr.strategy.operational.StrategyExecutionMode;
import com.stokr.strategy.operational.StrategyExecutionModeService;
import com.stokr.strategy.operational.TradingSafeStartupGateService;
import com.stokr.strategy.runtime.SignalCooldownService;
import com.stokr.strategy.service.SignalEmissionGuardService;
import com.stokr.strategy.service.SignalQualityGateService;
import com.stokr.strategy.service.StrategyDailySignalCapService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates whether a symbol/strategy can execute through the production pipeline.
 * Used by the unified ADV terminal — no synthetic intelligence path.
 */
@Service
@RequiredArgsConstructor
public class SignalPipelineEligibilityService {

    private final TradingSafeStartupGateService safeStartupGateService;
    private final FeedHealthMonitorService feedHealthMonitorService;
    private final StrategyGeneratorIntegrityGate integrityGate;
    private final StrategySessionEntryGuardService sessionEntryGuard;
    private final SignalCooldownService signalCooldownService;
    private final SignalQualityGateService signalQualityGateService;
    private final SignalEmissionGuardService signalEmissionGuardService;
    private final StrategyDailySignalCapService dailySignalCapService;
    private final StrategyExecutionModeService executionModeService;

    @Value("${stokr.execution.live-trading-enabled:false}")
    private boolean liveTradingEnabled;

    @Value("${stokr.strategy.execution-modes.allow-live:false}")
    private boolean allowLive;

    public record EligibilityResult(
            String executionStatus,
            String pipelineStage,
            String rejectionCode,
            String rejectionMessage,
            String requestedMode,
            String effectiveMode,
            String qualityGate,
            String riskGate,
            int cooldownSecRemaining,
            List<String> lifecycle) {
    }

    public EligibilityResult evaluatePrePersist(
            String strategyKey,
            String symbol,
            StrategySignalEntity preview,
            Instant now) {
        List<String> lifecycle = new ArrayList<>();
        lifecycle.add("DETECTED");

        if (!safeStartupGateService.isTradingReady(now)) {
            return blocked("BLOCKED", "FEED_STALE", "Platform warmup incomplete — safe startup gate not ready",
                    strategyKey, lifecycle);
        }

        if (!feedHealthMonitorService.isHealthyForLiveExecution(now)) {
            FeedHealthMonitorService.FeedHealthSnapshot feed = feedHealthMonitorService.snapshot(now);
            return blocked("BLOCKED", "FEED_STALE",
                    "Market feed unhealthy (equityStale=" + feed.equityStale()
                            + ", indexStale=" + feed.indexStale() + ", level=" + feed.level() + ")",
                    strategyKey, lifecycle);
        }
        lifecycle.add("VALIDATED");

        if (!integrityGate.isStrategyScanAllowed(strategyKey, now)) {
            return blocked("BLOCKED", "INTEGRITY_BLOCKED",
                    "Strategy integrity gate — NIFTY opening session or OBI data unavailable",
                    strategyKey, lifecycle);
        }

        if (!integrityGate.passPreEvaluate(strategyKey, symbol, now)) {
            return blocked("BLOCKED", "OBI_UNAVAILABLE",
                    "Order flow / OBI pre-check failed for " + symbol,
                    strategyKey, lifecycle);
        }

        if (!sessionEntryGuard.isSessionEntryAllowed(strategyKey, symbol, now)) {
            return blocked("SESSION_BLOCKED", "SESSION_BLOCKED",
                    "Session entry guard — max entries or window restriction for " + symbol,
                    strategyKey, lifecycle);
        }

        int cooldownRemaining = signalCooldownService.cooldownRemainingSeconds(symbol, strategyKey, now);
        if (cooldownRemaining > 0) {
            return new EligibilityResult(
                    "COOLDOWN",
                    "COOLDOWN_BLOCKED",
                    "COOLDOWN",
                    "Strategy cooldown active — " + cooldownRemaining + "s remaining before re-entry",
                    executionModeService.modeFor(strategyKey).name(),
                    resolveEffectiveMode(strategyKey),
                    "SKIPPED",
                    "SKIPPED",
                    cooldownRemaining,
                    lifecycle);
        }

        if (dailySignalCapService.isOverCap(strategyKey, now)) {
            return blocked("REJECTED", "DAILY_CAP",
                    "Daily signal cap reached for " + strategyKey,
                    strategyKey, lifecycle);
        }

        if (preview != null) {
            String qualityReason = signalQualityGateService.dropReason(preview);
            if (qualityReason != null) {
                lifecycle.add("QUALITY_CHECK");
                return new EligibilityResult(
                        "QUALITY_REJECTED",
                        "REJECTED",
                        "QUALITY_GATE",
                        qualityReason,
                        executionModeService.modeFor(strategyKey).name(),
                        resolveEffectiveMode(strategyKey),
                        "FAILED",
                        "PASSED",
                        0,
                        lifecycle);
            }
            lifecycle.add("QUALITY_PASSED");

            if (signalEmissionGuardService.shouldSuppress(preview)) {
                return blocked("REJECTED", "DUPLICATE",
                        "Duplicate signal suppressed (DB dedup window)",
                        strategyKey, lifecycle);
            }
        }

        StrategyExecutionMode mode = executionModeService.modeFor(strategyKey);
        String requested = mode.name();
        String effective = resolveEffectiveMode(strategyKey);
        lifecycle.add("RISK_APPROVED");
        lifecycle.add("OMS_ELIGIBLE");

        String status = "EXECUTABLE";
        if (mode.skipsBrokerExecution()) {
            status = "WATCHLIST";
        } else if ("LIVE".equals(requested) && !"LIVE".equals(effective)) {
            status = "WATCHLIST";
        }

        return new EligibilityResult(
                status,
                "OMS_ELIGIBLE",
                null,
                null,
                requested,
                effective,
                preview != null ? "PASSED" : "N/A",
                "PASSED",
                0,
                lifecycle);
    }

    public EligibilityResult enrichPersistedSignal(StrategySignalEntity signal) {
        List<String> lifecycle = new ArrayList<>();
        lifecycle.add("DETECTED");
        lifecycle.add("VALIDATED");
        lifecycle.add("QUALITY_PASSED");
        lifecycle.add("RISK_APPROVED");
        lifecycle.add("OMS_ELIGIBLE");

        String requested = signal.getPipeline() != null ? signal.getPipeline() : "PAPER";
        String effective = resolveEffectiveMode(signal.getStrategyName());

        String outcome = signal.getOutcomeStatus();
        String status;
        String stage;
        if (outcome != null && !outcome.isBlank() && !"PENDING".equalsIgnoreCase(outcome)) {
            status = mapOutcomeStatus(outcome);
            stage = "EXECUTED";
            lifecycle.add("EXECUTED");
        } else {
            status = "EXECUTABLE";
            stage = "OMS_ELIGIBLE";
            lifecycle.add("OMS_ELIGIBLE");
        }

        return new EligibilityResult(
                status,
                stage,
                null,
                null,
                requested,
                effective,
                "PASSED",
                "PASSED",
                0,
                lifecycle);
    }

    public EligibilityResult withOmsRejection(EligibilityResult base, String blockCode, String blockMessage, String effectiveMode) {
        List<String> lifecycle = new ArrayList<>(base.lifecycle());
        lifecycle.add("EXECUTION_FAILED");
        return new EligibilityResult(
                "OMS_REJECTED",
                "EXECUTION_FAILED",
                blockCode,
                blockMessage,
                base.requestedMode(),
                effectiveMode != null ? effectiveMode : base.effectiveMode(),
                base.qualityGate(),
                base.riskGate(),
                0,
                lifecycle);
    }

    public Map<String, Object> liveControlPanel(Instant now) {
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("liveEnabled", liveTradingEnabled && allowLive);
        panel.put("platformLiveFlag", liveTradingEnabled);
        panel.put("liveGateOpen", allowLive);

        FeedHealthMonitorService.FeedHealthSnapshot feed = feedHealthMonitorService.snapshot(now);
        panel.put("feedEquityStale", feed.equityStale());
        panel.put("feedIndexStale", feed.indexStale());
        panel.put("websocketConnected", feed.websocketConnected());
        panel.put("tickGapSeconds", feed.tickGapSeconds());

        boolean candlesFresh = !feed.equityStale() && !feed.indexStale();
        boolean ticksLive = feed.websocketConnected() && !feed.tickStale();
        boolean feedOperational = candlesFresh || ticksLive;
        boolean feedWarmup = !candlesFresh && ticksLive;
        panel.put("feedOperational", feedOperational);
        panel.put("feedWarmup", feedWarmup);
        panel.put("feedStatus", feedOperational
                ? (feedWarmup ? "WARMUP" : "OPERATIONAL")
                : (feed.websocketConnected() ? "RECOVERING" : "STALE"));

        panel.put("safeStartupReady", safeStartupGateService.isTradingReady(now));
        panel.put("marketOpen", NseMarketSession.isRegularSessionOpen(now));
        panel.put("sessionState", NseMarketSession.sessionState(now).name());
        panel.put("scanIntervalSec", scanIntervalSec());
        return panel;
    }

    @Value("${stokr.catalog.scan.poll-ms:10000}")
    private long catalogScanPollMs;

    private int scanIntervalSec() {
        return (int) Math.max(1, catalogScanPollMs / 1000);
    }

    private String resolveEffectiveMode(String strategyKey) {
        StrategyExecutionMode mode = executionModeService.modeFor(strategyKey);
        if ((mode == StrategyExecutionMode.LIVE || mode == StrategyExecutionMode.BOTH)
                && (!liveTradingEnabled || !allowLive)) {
            return "PAPER";
        }
        return mode.name();
    }

    private static EligibilityResult blocked(
            String status,
            String code,
            String message,
            String strategyKey,
            List<String> lifecycle) {
        return new EligibilityResult(
                status,
                "REJECTED",
                code,
                message,
                null,
                null,
                "SKIPPED",
                "SKIPPED",
                0,
                lifecycle);
    }

    private static String mapOutcomeStatus(String outcome) {
        return switch (outcome.toUpperCase()) {
            case "TARGET_HIT", "SL_HIT", "CLOSED", "PRESSURE_EXIT" -> "EXECUTED";
            case "EXPIRED" -> "REJECTED";
            default -> "EXECUTED";
        };
    }

}
