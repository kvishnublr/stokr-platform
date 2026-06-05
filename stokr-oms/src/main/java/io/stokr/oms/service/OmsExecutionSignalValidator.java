package io.stokr.oms.service;

import io.stokr.oms.domain.entity.OmsExecution;
import io.stokr.oms.domain.entity.OmsOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OmsExecutionSignalValidator {

    public void validateSignalLinkage(OmsExecution execution, OmsOrder order) {
        if (execution == null || order == null) {
            throw new IllegalArgumentException("Execution and Order cannot be null");
        }

        // CRITICAL RULE: LIVE mode MUST have signal_id
        if ("LIVE".equals(order.getExecutionMode())) {
            if (order.getSignalId() == null) {
                log.error("SIGNAL_LINKAGE_VIOLATION: LIVE execution has NO signal_id. order={} execution={}",
                    order.getId(), execution.getId());
                throw new IllegalStateException(
                    "LIVE execution requires signal_id linkage. Order: " + order.getId());
            }

            // Verify signal_id matches
            if (!order.getSignalId().equals(execution.getSignalId())) {
                log.error("SIGNAL_LINKAGE_MISMATCH: order.signal_id={} execution.signal_id={}",
                    order.getSignalId(), execution.getSignalId());
                // Note: This could be allowable if execution is synthetic
                if (!isExecutionSynthetic(execution)) {
                    throw new IllegalStateException("Signal ID mismatch between order and execution");
                }
            }
        }

        log.debug("Signal linkage validated: order={} signal={} synthetic={}",
            order.getId(), order.getSignalId(), isExecutionSynthetic(execution));
    }

    public void validatePaperModeSignalOptional(OmsExecution execution, OmsOrder order) {
        if ("PAPER".equals(order.getExecutionMode()) || "SIMULATION".equals(order.getExecutionMode())) {
            // Signal ID is optional for PAPER/SIMULATION
            log.debug("Signal validation skipped for {} execution mode", order.getExecutionMode());
        }
    }

    public boolean hasValidSignalLinkage(OmsExecution execution) {
        return execution.getSignalId() != null;
    }

    public boolean isExecutionSynthetic(OmsExecution execution) {
        return execution.getIsSynthetic() != null && execution.getIsSynthetic();
    }

    public void detectOrphanExecutions() {
        // TODO: Scan all LIVE executions without signal_id
        // Log them as critical issues
        log.info("Scanning for orphan executions (LIVE mode without signal_id)...");
    }
}
