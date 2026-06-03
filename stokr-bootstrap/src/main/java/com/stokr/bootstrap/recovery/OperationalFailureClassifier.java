package com.stokr.bootstrap.recovery;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class OperationalFailureClassifier {

    private final PlatformRecoveryProperties properties;

    public OperationalFailureClassifier(PlatformRecoveryProperties properties) {
        this.properties = properties;
    }

    public boolean isHealthy(OperationalRecoveryContext ctx) {
        if (ctx.requiresUserOAuth() || ctx.ingestionPausedByOperator()) {
            return true;
        }
        if (isDisconnected(ctx.database()) || isDisconnected(ctx.redis())) {
            return false;
        }
        if (ctx.killSwitchActive() && isRecoverableKillSwitch(ctx)) {
            return false;
        }
        if (isBrokerFeedUnhealthy(ctx)) {
            return false;
        }
        if (isScannerStalled(ctx)) {
            return false;
        }
        if (!ctx.actuatorHealthy()) {
            return false;
        }
        return true;
    }

    public OperationalFailureSignature classify(OperationalRecoveryContext ctx) {
        if (ctx.requiresUserOAuth() || hasAuthSignature(ctx)) {
            return OperationalFailureSignature.BAD_AUTH;
        }
        if (isDisconnected(ctx.database())) {
            return OperationalFailureSignature.DB_UNREACHABLE;
        }
        if (isDisconnected(ctx.redis())) {
            return OperationalFailureSignature.REDIS_UNREACHABLE;
        }
        if (ctx.killSwitchActive() && isRecoverableKillSwitch(ctx)) {
            return OperationalFailureSignature.KILL_SWITCH_ACTIVE;
        }
        if (isBrokerFeedUnhealthy(ctx)) {
            return OperationalFailureSignature.BROKER_FEED_DOWN;
        }
        if (isScannerStalled(ctx)) {
            return OperationalFailureSignature.SCANNER_STALLED;
        }
        return OperationalFailureSignature.UNKNOWN;
    }

    public boolean isBroadOutage(OperationalRecoveryContext ctx) {
        return isDisconnected(ctx.database()) && isDisconnected(ctx.redis());
    }

    public Set<OperationalFailureSignature> activeSignatures(OperationalRecoveryContext ctx) {
        EnumSet<OperationalFailureSignature> set = EnumSet.noneOf(OperationalFailureSignature.class);
        if (isDisconnected(ctx.database())) {
            set.add(OperationalFailureSignature.DB_UNREACHABLE);
        }
        if (isDisconnected(ctx.redis())) {
            set.add(OperationalFailureSignature.REDIS_UNREACHABLE);
        }
        if (ctx.killSwitchActive() && isRecoverableKillSwitch(ctx)) {
            set.add(OperationalFailureSignature.KILL_SWITCH_ACTIVE);
        }
        if (isBrokerFeedUnhealthy(ctx)) {
            set.add(OperationalFailureSignature.BROKER_FEED_DOWN);
        }
        if (isScannerStalled(ctx)) {
            set.add(OperationalFailureSignature.SCANNER_STALLED);
        }
        if (ctx.requiresUserOAuth() || hasAuthSignature(ctx)) {
            set.add(OperationalFailureSignature.BAD_AUTH);
        }
        if (set.isEmpty() && !isHealthy(ctx)) {
            set.add(OperationalFailureSignature.UNKNOWN);
        }
        return set;
    }

    private static boolean isDisconnected(Map<String, Object> probe) {
        return "DISCONNECTED".equals(String.valueOf(probe.get("status")));
    }

    private static boolean hasAuthSignature(OperationalRecoveryContext ctx) {
        return ctx.errorSignatures().stream().anyMatch(s -> s.startsWith("BROKER_AUTH")
                || s.contains("403")
                || s.contains("AUTH_EXPIRED"));
    }

    private boolean isRecoverableKillSwitch(OperationalRecoveryContext ctx) {
        Object source = ctx.killSwitchDetail().get("lastEventSource");
        if (source == null) {
            return false;
        }
        return properties.getKillSwitchAutoDisarmSources().stream()
                .anyMatch(s -> s.equalsIgnoreCase(String.valueOf(source)));
    }

    private static boolean isBrokerFeedUnhealthy(OperationalRecoveryContext ctx) {
        if (Boolean.TRUE.equals(ctx.brokerFeed().get("operationalLivePath"))) {
            return false;
        }
        if (Boolean.TRUE.equals(ctx.brokerFeed().get("reconnecting"))) {
            return false;
        }
        if (Boolean.FALSE.equals(ctx.brokerFeed().get("operationalLivePath"))) {
            return true;
        }
        String level = String.valueOf(ctx.feedHealth().getOrDefault("level", "OK"));
        if ("ERROR".equals(level)) {
            return true;
        }
        return ctx.errorSignatures().stream().anyMatch(s -> s.startsWith("BROKER_FEED_DOWN"));
    }

    private boolean isScannerStalled(OperationalRecoveryContext ctx) {
        if (ctx.activeScannerBindings() <= 0) {
            return false;
        }
        if (!Boolean.TRUE.equals(ctx.executionPipeline().get("executionPipelineActive"))) {
            return true;
        }
        Object completedAt = ctx.scannerTelemetry().get("lastPollCompletedAt");
        if (completedAt == null) {
            return true;
        }
        if (Boolean.TRUE.equals(ctx.scannerTelemetry().get("lastPollWasSkipped"))) {
            String reason = String.valueOf(ctx.scannerTelemetry().get("lastPollSkipReason"));
            if (reason != null && !reason.isBlank() && !"unspecified".equals(reason)) {
                return true;
            }
        }
        try {
            Instant lastPoll = Instant.parse(String.valueOf(completedAt));
            long ageSec = Duration.between(lastPoll, ctx.collectedAt()).getSeconds();
            return ageSec > properties.getScannerStaleSeconds();
        } catch (Exception ignored) {
            return true;
        }
    }
}
