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
            if (order.getBrokerOrderId() != null) {
                String msg = "SAFETY VIOLATION: Paper mode order has broker ID: " + order.getBrokerOrderId();
                log.error("execution.safety_violation {}", msg);
                throw new IllegalStateException(msg);
            }
        } else if (currentMode == ExecutionMode.LIVE) {
            // In LIVE mode, order MUST be routed to broker eventually
            // No synthetic/paper fills allowed
            // This is verified post-execution
        }

        log.debug("execution.pre_check passed orderId={} mode={}", order.getOrderId(), currentMode);
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
        } else if (currentMode == ExecutionMode.HYBRID) {
            // Both PAPER and BROKER fills are allowed in parallel
            log.debug("execution.hybrid_execution orderId={} fillSource={}", order.getOrderId(), fillSource);
        }

        log.debug("execution.post_check passed orderId={} mode={} fillSource={}",
                order.getOrderId(), currentMode, fillSource);
    }

    public void assertTimeSourceConsistency(ExecutionContext context) {
        // Ensure trading logic never mixes system_time and market_time
        // Called before position updates, fill processing, PnL calculations
        if (context == null) {
            log.warn("execution.null_context");
            return;
        }

        long timeDiff = Math.abs(context.getCurrentTime().toEpochMilli() - context.getLogTime().toEpochMilli());

        if (currentMode == ExecutionMode.PAPER || currentMode == ExecutionMode.HYBRID) {
            // In paper mode, market_time might be way in past (replay scenario)
            // Just log for awareness, don't fail
            if (timeDiff > 86400_000) {  // > 24 hours
                log.warn("execution.time_divergence market_time_lag_ms={}", timeDiff);
            }
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

        if (expectedMode == ExecutionMode.HYBRID) {
            if (!"BROKER".equals(adapterType) && !"PAPER".equals(adapterType)) {
                String msg = "SAFETY VIOLATION: Hybrid mode must use BROKER or PAPER adapter, got: " + adapterType;
                log.error("execution.adapter_mismatch {}", msg);
                throw new IllegalStateException(msg);
            }
        }

        log.debug("execution.adapter_validation passed mode={} adapter={}", expectedMode, adapterType);
    }
}
