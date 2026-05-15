package com.stokr.common.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Central runtime guard for signal->OMS->execution async routing safety.
 */
@Service
public class ExecutionPipelineRuntimeReadinessService {

    @Value("${stokr.rabbit.listeners-enabled:false}")
    private boolean rabbitListenersEnabled;

    @Value("${stokr.strategy.poll-execution-mode:PAPER}")
    private String pollExecutionMode;

    public ExecutionPipelineReadiness snapshot() {
        String mode = normalizedPollExecutionMode();
        boolean requiresRabbit = "LIVE".equals(mode) || "PAPER".equals(mode);
        boolean active = !requiresRabbit || rabbitListenersEnabled;

        String detail;
        if (!requiresRabbit) {
            detail = "Execution mode " + mode + " does not require Rabbit consumers.";
        } else if (!rabbitListenersEnabled) {
            detail = "EXECUTION PIPELINE DISABLED: Rabbit listeners are OFF. Signal routing and OMS execution are inactive.";
        } else {
            detail = "Rabbit listeners are ON for " + mode + " scanner execution mode.";
        }
        return new ExecutionPipelineReadiness(
                mode,
                requiresRabbit,
                rabbitListenersEnabled,
                active,
                detail
        );
    }

    public boolean canRouteExecutionMode(String executionMode) {
        if (executionMode == null || executionMode.isBlank()) {
            return true;
        }
        String mode = executionMode.trim().toUpperCase();
        if ("LIVE".equals(mode) || "PAPER".equals(mode)) {
            return rabbitListenersEnabled;
        }
        return true;
    }

    private String normalizedPollExecutionMode() {
        if (pollExecutionMode == null || pollExecutionMode.isBlank()) {
            return "PAPER";
        }
        String mode = pollExecutionMode.trim().toUpperCase();
        if ("LIVE".equals(mode) || "PAPER".equals(mode) || "SIMULATED".equals(mode)) {
            return mode;
        }
        return "PAPER";
    }

    public record ExecutionPipelineReadiness(
            String pollExecutionMode,
            boolean pollModeRequiresRabbit,
            boolean rabbitListenersEnabled,
            boolean executionPipelineActive,
            String detail
    ) {
    }
}
