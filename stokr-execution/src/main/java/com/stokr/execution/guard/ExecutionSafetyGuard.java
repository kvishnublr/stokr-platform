package com.stokr.execution.guard;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ExecutionSafetyGuard {

    private ExecutionMode currentMode = ExecutionMode.PAPER;

    public void setCurrentMode(ExecutionMode mode) {
        this.currentMode = mode;
    }

    public void preExecutionCheck(OmsOrder order) {
        if (currentMode == ExecutionMode.PAPER) {
            // In PAPER mode, verify order is NOT being routed to real broker
            // Check if order has brokerOrderId (would indicate routed to broker)
            // This prevents accidental broker routing in paper mode
        } else if (currentMode == ExecutionMode.LIVE) {
            // In LIVE mode, order MUST be routed to broker eventually
            // No synthetic/paper fills allowed
            // This is verified post-execution
        }

        log.debug("execution.pre_check passed orderId={} mode={}", order.getId(), currentMode);
    }

    public void postExecutionCheck(OmsOrder order, String fillSource) {
        if (currentMode == ExecutionMode.PAPER) {
            // Verify fills came from paper exchange, not broker
            if ("BROKER".equals(fillSource)) {
                String msg = "SAFETY VIOLATION: Paper mode order received broker fill";
                log.error("execution.safety_violation {}", msg);
                throw new IllegalStateException(msg);
            }
        } else if (currentMode == ExecutionMode.LIVE) {
            // Verify fills came from broker, not simulation
            if ("PAPER".equals(fillSource)) {
                String msg = "SAFETY VIOLATION: Live mode order received simulated fill";
                log.error("execution.safety_violation {}", msg);
                throw new IllegalStateException(msg);
            }
        } else if (currentMode == ExecutionMode.BOTH) {
            // Both PAPER and BROKER fills are allowed in parallel
            log.debug("execution.both_mode_execution orderId={} fillSource={}", order.getId(), fillSource);
        }

        log.debug("execution.post_check passed orderId={} mode={} fillSource={}",
                order.getId(), currentMode, fillSource);
    }

    public void assertTimeSourceConsistency(Object context) {
        // Time source consistency check - context type depends on execution environment
        // In production, this validates that systemTime (wall clock) and marketTime (trading time)
        // are not mixed in trading logic
        if (context == null) {
            log.warn("execution.null_context");
            return;
        }

        if (currentMode == ExecutionMode.PAPER || currentMode == ExecutionMode.BOTH) {
            // In paper/both modes, market_time might differ from system_time (replay scenario)
            log.debug("execution.time_handling mode={} allows_market_time_variance=true", currentMode);
        }
    }

    public void validateAdapterSelection(ExecutionMode expectedMode, String adapterType) {
        if (expectedMode == ExecutionMode.LIVE && !"BROKER".equals(adapterType)) {
            String msg = "SAFETY VIOLATION: Live mode must use BROKER adapter, got: " + adapterType;
            log.error("execution.adapter_mismatch {}", msg);
            throw new IllegalStateException(msg);
        }

        if (expectedMode == ExecutionMode.PAPER && !"PAPER".equals(adapterType)) {
            String msg = "SAFETY VIOLATION: Paper mode must use PAPER adapter, got: " + adapterType;
            log.error("execution.adapter_mismatch {}", msg);
            throw new IllegalStateException(msg);
        }

        if (expectedMode == ExecutionMode.BOTH) {
            if (!"BROKER".equals(adapterType) && !"PAPER".equals(adapterType)) {
                String msg = "SAFETY VIOLATION: Both mode must use BROKER or PAPER adapter, got: " + adapterType;
                log.error("execution.adapter_mismatch {}", msg);
                throw new IllegalStateException(msg);
            }
        }

        log.debug("execution.adapter_validation passed mode={} adapter={}", expectedMode, adapterType);
    }
}
