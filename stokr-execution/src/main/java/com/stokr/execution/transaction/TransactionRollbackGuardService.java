package com.stokr.execution.transaction;

import com.stokr.execution.tracking.SignalExecutionTrackingService;
import com.stokr.execution.tracking.SignalExecutionTrack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionRollbackGuardService {

    private final SignalExecutionTrackingService trackingService;

    /**
     * Register transaction completion listener to detect rollback-only errors
     * This prevents silent transaction failures
     */
    public void registerRollbackGuard(UUID signalId, UUID userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("transaction.guard.no_active_tx signalId={}", signalId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    handleTransactionRollback(signalId, userId);
                } else if (status == STATUS_COMMITTED) {
                    log.debug("transaction.committed signalId={}", signalId);
                }
            }

            @Override
            public void beforeCompletion() {
                // Pre-completion hook for additional checks if needed
                log.debug("transaction.before_completion signalId={}", signalId);
            }
        });
    }

    /**
     * Handle unexpected transaction rollback
     * Logs error and updates tracking to prevent silent failure
     */
    private void handleTransactionRollback(UUID signalId, UUID userId) {
        log.error("transaction.rollback.detected signalId={} userId={}", signalId, userId);

        // Record failure for tracking/dashboard visibility
        trackingService.recordFailure(signalId, userId,
                SignalExecutionTrack.SignalExecutionStatus.REJECTED,
                "TRANSACTION_ROLLBACK",
                "Transaction rolled back - check database constraints and error logs");

        // Alert operations team
        logCriticalAlert(signalId, userId);
    }

    /**
     * Log critical alert for operations monitoring
     */
    private void logCriticalAlert(UUID signalId, UUID userId) {
        log.error("ALERT! transaction.rollback.silent signalId={} userId={} action=INVESTIGATE_IMMEDIATELY",
                signalId, userId);
        // In production, would also send to alerting system (PagerDuty, etc.)
    }

    /**
     * Validate transaction state before critical operations
     */
    public void validateTransactionState(UUID signalId, UUID userId) throws IllegalStateException {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            String error = "No active transaction for signal execution";
            log.error("transaction.not_active signalId={} error={}", signalId, error);
            trackingService.recordFailure(signalId, userId,
                    SignalExecutionTrack.SignalExecutionStatus.REJECTED,
                    "NO_ACTIVE_TRANSACTION",
                    error);
            throw new IllegalStateException(error);
        }
    }

    /**
     * Catch-all exception handler for transaction issues
     */
    @Transactional(rollbackFor = Exception.class)
    public void executeWithRollbackProtection(UUID signalId, UUID userId, TransactionTask task) throws Exception {
        try {
            registerRollbackGuard(signalId, userId);
            validateTransactionState(signalId, userId);
            task.execute();
            log.info("transaction.success signalId={} userId={}", signalId, userId);
        } catch (Exception ex) {
            log.error("transaction.execution_failed signalId={} userId={} error={}",
                    signalId, userId, ex.getMessage(), ex);
            trackingService.recordFailure(signalId, userId,
                    SignalExecutionTrack.SignalExecutionStatus.REJECTED,
                    "TRANSACTION_FAILURE",
                    "Exception during transaction: " + ex.getMessage());
            throw ex;
        }
    }

    /**
     * Functional interface for transaction tasks
     */
    @FunctionalInterface
    public interface TransactionTask {
        void execute() throws Exception;
    }
}
