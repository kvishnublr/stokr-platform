package com.stokr.execution.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/admin/execution/config")
@RequiredArgsConstructor
@Slf4j
public class ExecutionConfigurationController {

    private final ExecutionConfiguration executionConfig;
    private final List<ConfigChangeAudit> auditTrail = new ArrayList<>();

    @GetMapping
    public ConfigSnapshot getConfiguration() {
        return buildSnapshot();
    }

    @PostMapping("/paper/slippage")
    public ConfigSnapshot updatePaperSlippage(
            @RequestParam double slippageBps,
            @RequestParam(required = false) String reason) {

        double oldValue = executionConfig.getPaper().getSlippageBps();
        executionConfig.getPaper().setSlippageBps(slippageBps);

        auditChange("PAPER_SLIPPAGE", String.valueOf(oldValue), String.valueOf(slippageBps), reason);

        log.info("config.paper_slippage_changed old={} new={} reason={}", oldValue, slippageBps, reason);

        return buildSnapshot();
    }

    @PostMapping("/paper/latency")
    public ConfigSnapshot updatePaperLatency(
            @RequestParam long minMs,
            @RequestParam long maxMs,
            @RequestParam(required = false) String reason) {

        long oldMinMs = executionConfig.getPaper().getLatencyMinMs();
        long oldMaxMs = executionConfig.getPaper().getLatencyMaxMs();

        executionConfig.getPaper().setLatencyMinMs(minMs);
        executionConfig.getPaper().setLatencyMaxMs(maxMs);

        auditChange("PAPER_LATENCY",
                String.format("%d-%d", oldMinMs, oldMaxMs),
                String.format("%d-%d", minMs, maxMs),
                reason);

        log.info("config.paper_latency_changed minMs={} maxMs={} reason={}", minMs, maxMs, reason);

        return buildSnapshot();
    }

    @PostMapping("/paper/margin")
    public ConfigSnapshot updatePaperMargin(
            @RequestParam double marginMultiplier,
            @RequestParam(required = false) String reason) {

        double oldValue = executionConfig.getPaper().getMarginMultiplier();
        executionConfig.getPaper().setMarginMultiplier(marginMultiplier);

        auditChange("PAPER_MARGIN", String.valueOf(oldValue), String.valueOf(marginMultiplier), reason);

        log.info("config.paper_margin_changed multiplier={} reason={}", marginMultiplier, reason);

        return buildSnapshot();
    }

    @PostMapping("/replay/speed")
    public ConfigSnapshot updateReplaySpeed(
            @RequestParam double speed,
            @RequestParam(required = false) String reason) {

        if (speed < executionConfig.getReplay().getMinSpeed() || speed > executionConfig.getReplay().getMaxSpeed()) {
            throw new IllegalArgumentException("Speed must be between " +
                    executionConfig.getReplay().getMinSpeed() + " and " +
                    executionConfig.getReplay().getMaxSpeed());
        }

        double oldValue = executionConfig.getReplay().getDefaultSpeed();
        executionConfig.getReplay().setDefaultSpeed(speed);

        auditChange("REPLAY_SPEED", String.valueOf(oldValue), String.valueOf(speed), reason);

        log.info("config.replay_speed_changed speed={}x reason={}", speed, reason);

        return buildSnapshot();
    }

    @PostMapping("/synthetic/volatility")
    public ConfigSnapshot updateSyntheticVolatility(
            @RequestParam double volatility,
            @RequestParam(required = false) String reason) {

        double oldValue = executionConfig.getSynthetic().getVolatility();
        executionConfig.getSynthetic().setVolatility(volatility);

        auditChange("SYNTHETIC_VOLATILITY", String.valueOf(oldValue), String.valueOf(volatility), reason);

        log.info("config.synthetic_volatility_changed volatility={} reason={}", volatility, reason);

        return buildSnapshot();
    }

    @PostMapping("/safety/drift-threshold")
    public ConfigSnapshot updateDriftThreshold(
            @RequestParam double driftThresholdBps,
            @RequestParam(required = false) String reason) {

        double oldValue = executionConfig.getSafety().getReconciliationDriftThresholdBps();
        executionConfig.getSafety().setReconciliationDriftThresholdBps(driftThresholdBps);

        auditChange("RECONCILIATION_DRIFT_THRESHOLD",
                String.valueOf(oldValue), String.valueOf(driftThresholdBps), reason);

        log.info("config.drift_threshold_changed threshold_bps={} reason={}", driftThresholdBps, reason);

        return buildSnapshot();
    }

    @GetMapping("/audit")
    public List<ConfigChangeAudit> getAuditTrail(
            @RequestParam(defaultValue = "50") int limit) {
        int start = Math.max(0, auditTrail.size() - limit);
        return new ArrayList<>(auditTrail.subList(start, auditTrail.size()));
    }

    @PostMapping("/audit/clear")
    public void clearAuditTrail() {
        auditTrail.clear();
        log.info("config.audit_cleared");
    }

    @PostMapping("/validate")
    public ConfigValidationResult validate() {
        try {
            executionConfig.validate();
            return new ConfigValidationResult(true, "Configuration is valid", Collections.emptyList());
        } catch (Exception e) {
            return new ConfigValidationResult(false, e.getMessage(), Collections.singletonList(e.getMessage()));
        }
    }

    @PostMapping("/reset")
    public ConfigSnapshot reset(@RequestParam(required = false) String reason) {
        auditChange("RESET", "current", "defaults", reason);
        executionConfig.validate();
        log.info("config.reset reason={}", reason);
        return buildSnapshot();
    }

    private ConfigSnapshot buildSnapshot() {
        return ConfigSnapshot.builder()
                .mode(executionConfig.getMode())
                .paper(ConfigSnapshot.PaperSnapshot.builder()
                        .startingCapital(executionConfig.getPaper().getStartingCapital().toString())
                        .slippageBps(executionConfig.getPaper().getSlippageBps())
                        .latencyMinMs(executionConfig.getPaper().getLatencyMinMs())
                        .latencyMaxMs(executionConfig.getPaper().getLatencyMaxMs())
                        .marginMultiplier(executionConfig.getPaper().getMarginMultiplier())
                        .partialFillsEnabled(executionConfig.getPaper().isPartialFillsEnabled())
                        .build())
                .replay(ConfigSnapshot.ReplaySnapshot.builder()
                        .enabled(executionConfig.getReplay().isEnabled())
                        .defaultSpeed(executionConfig.getReplay().getDefaultSpeed())
                        .minSpeed(executionConfig.getReplay().getMinSpeed())
                        .maxSpeed(executionConfig.getReplay().getMaxSpeed())
                        .build())
                .synthetic(ConfigSnapshot.SyntheticSnapshot.builder()
                        .enabled(executionConfig.getSynthetic().isEnabled())
                        .defaultModel(executionConfig.getSynthetic().getDefaultModel())
                        .volatility(executionConfig.getSynthetic().getVolatility())
                        .deterministic(executionConfig.getSynthetic().isDeterministic())
                        .build())
                .safety(ConfigSnapshot.SafetySnapshot.builder()
                        .isolationEnforced(executionConfig.getSafety().isIsolationEnforced())
                        .reconciliationEnabled(executionConfig.getSafety().isReconciliationEnabled())
                        .driftThresholdBps(executionConfig.getSafety().getReconciliationDriftThresholdBps())
                        .build())
                .snapshotTime(Instant.now())
                .build();
    }

    private void auditChange(String parameter, String oldValue, String newValue, String reason) {
        ConfigChangeAudit audit = ConfigChangeAudit.builder()
                .parameter(parameter)
                .oldValue(oldValue)
                .newValue(newValue)
                .reason(reason)
                .timestamp(Instant.now())
                .changedBy("ADMIN")  // In production, get from SecurityContext
                .build();
        auditTrail.add(audit);
    }

    @lombok.Data
    @lombok.Builder
    public static class ConfigSnapshot {
        private String mode;
        private PaperSnapshot paper;
        private ReplaySnapshot replay;
        private SyntheticSnapshot synthetic;
        private SafetySnapshot safety;
        private Instant snapshotTime;

        @lombok.Data
        @lombok.Builder
        public static class PaperSnapshot {
            private String startingCapital;
            private double slippageBps;
            private long latencyMinMs;
            private long latencyMaxMs;
            private double marginMultiplier;
            private boolean partialFillsEnabled;
        }

        @lombok.Data
        @lombok.Builder
        public static class ReplaySnapshot {
            private boolean enabled;
            private double defaultSpeed;
            private double minSpeed;
            private double maxSpeed;
        }

        @lombok.Data
        @lombok.Builder
        public static class SyntheticSnapshot {
            private boolean enabled;
            private String defaultModel;
            private double volatility;
            private boolean deterministic;
        }

        @lombok.Data
        @lombok.Builder
        public static class SafetySnapshot {
            private boolean isolationEnforced;
            private boolean reconciliationEnabled;
            private double driftThresholdBps;
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class ConfigChangeAudit {
        private String parameter;
        private String oldValue;
        private String newValue;
        private String reason;
        private Instant timestamp;
        private String changedBy;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ConfigValidationResult {
        private boolean valid;
        private String message;
        private List<String> errors;
    }
}
