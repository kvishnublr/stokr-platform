package com.stokr.bootstrap.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Ranked recovery ladder: collect context → classify → one targeted fix → wait → recheck → escalate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RankedRecoveryOrchestrator {

    private static final Map<OperationalFailureSignature, List<RecoveryActionType>> LADDER = ladder();

    private final PlatformRecoveryProperties properties;
    private final OperationalRecoveryContextCollector contextCollector;
    private final OperationalFailureClassifier classifier;
    private final RecoveryStateStore stateStore;
    private final DeterministicRecoveryActions recoveryActions;
    private final RecoveryAlertPublisher alertPublisher;

    public void runRecoveryCycle() {
        OperationalRecoveryContext ctx = contextCollector.collect();

        if (ctx.requiresUserOAuth() || ctx.ingestionPausedByOperator()) {
            log.debug("platform.recovery skip reason={}",
                    ctx.requiresUserOAuth() ? "user_oauth_required" : "ingestion_paused_by_operator");
            return;
        }

        if (classifier.isHealthy(ctx)) {
            OperationalRecoveryState prior = stateStore.load();
            if (prior.failureSignature() != null || prior.consecutiveUnhealthyCycles() > 0) {
                OperationalRecoveryState resolved = prior.withSuccess(Instant.now());
                stateStore.save(resolved);
                alertPublisher.publishResolved(ctx, resolved);
            } else {
                stateStore.markSuccess();
            }
            return;
        }

        OperationalFailureSignature signature = choosePrimarySignature(ctx);
        OperationalRecoveryState state = stateStore.load();
        RecoveryActionType action = chooseNextAction(signature, state, ctx);

        if (action == RecoveryActionType.NONE) {
            log.debug("platform.recovery no_action signature={} state={}", signature, state);
            return;
        }

        if (action == RecoveryActionType.ESCALATE_HUMAN || action == RecoveryActionType.REQUEST_CONTAINER_RESTART) {
            Map<String, Object> result = recoveryActions.execute(action, ctx);
            OperationalRecoveryState escalated = state.withAction(signature, action, Instant.now());
            stateStore.save(escalated);
            alertPublisher.publishEscalation(ctx, signature, action, escalated, result);
            return;
        }

        log.info("platform.recovery executing signature={} action={} attempt={}",
                signature, action, state.attemptCount() + 1);
        Map<String, Object> actionResult = recoveryActions.execute(action, ctx);
        OperationalRecoveryState afterAction = state.withAction(signature, action, Instant.now());
        stateStore.save(afterAction);

        sleepRecheckWindow();

        OperationalRecoveryContext recheck = contextCollector.collect();
        if (classifier.isHealthy(recheck)) {
            OperationalRecoveryState resolved = afterAction.withSuccess(Instant.now());
            stateStore.save(resolved);
            alertPublisher.publishResolved(recheck, resolved);
            log.info("platform.recovery resolved signature={} action={}", signature, action);
            return;
        }

        OperationalFailureSignature afterSignature = choosePrimarySignature(recheck);
        if (afterSignature == signature) {
            if (afterAction.attemptCount() >= properties.getMaxAttemptsPerSignature()) {
                escalate(recheck, signature, action, afterAction);
            } else if (afterAction.consecutiveUnhealthyCycles() >= properties.getEscalateAfterAttempts()) {
                Map<String, Object> restartResult = recoveryActions.execute(
                        RecoveryActionType.REQUEST_CONTAINER_RESTART, recheck);
                OperationalRecoveryState restartState = afterAction.withAction(
                        signature, RecoveryActionType.REQUEST_CONTAINER_RESTART, Instant.now());
                stateStore.save(restartState);
                alertPublisher.publishEscalation(recheck, signature, RecoveryActionType.REQUEST_CONTAINER_RESTART,
                        restartState, restartResult);
            } else {
                log.warn("platform.recovery still_unhealthy signature={} action={} attempts={}",
                        signature, action, afterAction.attemptCount());
            }
        } else {
            log.info("platform.recovery signature_shift before={} after={}", signature, afterSignature);
        }
    }

    private OperationalFailureSignature choosePrimarySignature(OperationalRecoveryContext ctx) {
        if (classifier.isBroadOutage(ctx)) {
            return OperationalFailureSignature.DB_UNREACHABLE;
        }
        return classifier.classify(ctx);
    }

    RecoveryActionType chooseNextAction(
            OperationalFailureSignature signature,
            OperationalRecoveryState state,
            OperationalRecoveryContext ctx
    ) {
        if (signature == OperationalFailureSignature.BAD_AUTH) {
            return RecoveryActionType.ESCALATE_HUMAN;
        }
        if (signature == OperationalFailureSignature.UNKNOWN
                && state.consecutiveUnhealthyCycles() >= properties.getEscalateAfterAttempts()) {
            return RecoveryActionType.ESCALATE_HUMAN;
        }

        List<RecoveryActionType> ladder = LADDER.getOrDefault(signature, List.of(RecoveryActionType.ESCALATE_HUMAN));
        RecoveryActionType candidate = pickFromLadder(ladder, state);

        if (candidate != RecoveryActionType.NONE && isOnCooldown(candidate, state)) {
            return RecoveryActionType.NONE;
        }
        if (state.attemptCount() >= properties.getMaxAttemptsPerSignature()
                && state.failureSignature() == signature
                && state.lastActionTaken() == candidate) {
            int idx = ladder.indexOf(candidate);
            if (idx >= 0 && idx + 1 < ladder.size()) {
                candidate = ladder.get(idx + 1);
            } else if (state.consecutiveUnhealthyCycles() >= properties.getEscalateAfterAttempts()) {
                return RecoveryActionType.REQUEST_CONTAINER_RESTART;
            } else {
                return RecoveryActionType.ESCALATE_HUMAN;
            }
        }
        return candidate;
    }

    private RecoveryActionType pickFromLadder(List<RecoveryActionType> ladder, OperationalRecoveryState state) {
        if (ladder.isEmpty()) {
            return RecoveryActionType.ESCALATE_HUMAN;
        }
        if (state.failureSignature() == null || state.lastActionTaken() == null) {
            return ladder.getFirst();
        }
        int idx = ladder.indexOf(state.lastActionTaken());
        if (idx < 0) {
            return ladder.getFirst();
        }
        if (state.attemptCount() < properties.getMaxAttemptsPerSignature()) {
            return state.lastActionTaken();
        }
        return idx + 1 < ladder.size() ? ladder.get(idx + 1) : ladder.getLast();
    }

    private boolean isOnCooldown(RecoveryActionType action, OperationalRecoveryState state) {
        if (state.lastActionAt() == null) {
            return false;
        }
        long cooldownSec = switch (action) {
            case RECONNECT_BROKER_WS -> properties.getReconnectCooldownSec();
            case ACTIVATE_SIGNAL_PIPELINE -> properties.getPipelineCooldownSec();
            default -> properties.getActionCooldownSec();
        };
        long elapsed = Duration.between(state.lastActionAt(), Instant.now()).getSeconds();
        return elapsed < cooldownSec;
    }

    private void escalate(
            OperationalRecoveryContext ctx,
            OperationalFailureSignature signature,
            RecoveryActionType lastAction,
            OperationalRecoveryState state
    ) {
        Map<String, Object> result = recoveryActions.execute(RecoveryActionType.ESCALATE_HUMAN, ctx);
        OperationalRecoveryState escalated = state.withAction(signature, RecoveryActionType.ESCALATE_HUMAN, Instant.now());
        stateStore.save(escalated);
        alertPublisher.publishEscalation(ctx, signature, lastAction, escalated, result);
    }

    private void sleepRecheckWindow() {
        long waitMs = Math.min(
                properties.getRecheckWaitMaxMs(),
                Math.max(properties.getRecheckWaitMs(), properties.getRecheckWaitMs()));
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<OperationalFailureSignature, List<RecoveryActionType>> ladder() {
        Map<OperationalFailureSignature, List<RecoveryActionType>> map = new EnumMap<>(OperationalFailureSignature.class);
        map.put(OperationalFailureSignature.BROKER_FEED_DOWN, List.of(
                RecoveryActionType.REFRESH_BROKER_TOKENS,
                RecoveryActionType.RECONNECT_BROKER_WS));
        map.put(OperationalFailureSignature.KILL_SWITCH_ACTIVE, List.of(RecoveryActionType.DEACTIVATE_KILL_SWITCH));
        map.put(OperationalFailureSignature.SCANNER_STALLED, List.of(RecoveryActionType.ACTIVATE_SIGNAL_PIPELINE));
        map.put(OperationalFailureSignature.DB_UNREACHABLE, List.of(RecoveryActionType.HEAL_DB_POOL));
        map.put(OperationalFailureSignature.REDIS_UNREACHABLE, List.of(RecoveryActionType.HEAL_REDIS));
        map.put(OperationalFailureSignature.UNKNOWN, List.of(RecoveryActionType.ESCALATE_HUMAN));
        return Map.copyOf(map);
    }
}
