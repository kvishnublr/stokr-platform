package com.stokr.execution.pipeline;

import com.stokr.execution.capital.StrategyCapitalReservationService;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.oms.trace.ExecutionTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Linked execution: LIVE → PAPER synchronization.
 * Ensures both orders start with same entry price and execute together or fail together.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LinkedExecutionService {

    private final OrderLifecycleService orderLifecycleService;
    private final StrategyCapitalReservationService capitalReservationService;
    private final ExecutionTraceService executionTraceService;

    /**
     * Link two orders (LIVE and PAPER) so execution is synchronized.
     * PAPER executes ONLY if LIVE succeeds.
     */
    @Transactional
    public void linkOrdersForSynchronizedExecution(OmsOrder liveOrder, OmsOrder paperOrder, UUID signalId) {
        if (liveOrder == null || paperOrder == null) {
            log.warn("linked_execution.null_order signalId={} live={} paper={}",
                    signalId, liveOrder, paperOrder);
            return;
        }

        // Set paired references (orders are already in DB, just referencing each other)
        liveOrder.setPairedOrderId(paperOrder.getId());
        liveOrder.setExecutionLinkage("SYNC_PAPER");
        liveOrder.setExecutionLinkageReason("LIVE execution gates PAPER execution");

        paperOrder.setPairedOrderId(liveOrder.getId());
        paperOrder.setExecutionLinkage("GATED_BY_LIVE");
        paperOrder.setExecutionLinkageReason("Waits for LIVE execution result");

        log.info("linked_execution.orders_paired signalId={} live={} paper={}",
                signalId, liveOrder.getId(), paperOrder.getId());
    }

    /**
     * Record LIVE execution failure. PAPER should NOT be executed.
     */
    @Transactional
    public void recordLiveExecutionFailure(OmsOrder liveOrder, String failureReason) {
        if (liveOrder == null || liveOrder.getId() == null) {
            log.warn("linked_execution.record_failure.null_order liveOrder={}", liveOrder);
            return;
        }

        UUID pairedOrderId = liveOrder.getPairedOrderId();
        if (pairedOrderId == null) {
            log.warn("linked_execution.record_failure.no_paired_order liveOrderId={}", liveOrder.getId());
            return;
        }

        // Fetch paired PAPER order
        try {
            OmsOrder paperOrder = orderLifecycleService.getRequired(pairedOrderId);

            // REJECT PAPER order since LIVE failed
            if (paperOrder.getState() == OrderState.PENDING_SUBMISSION ||
                paperOrder.getState() == OrderState.CREATED) {
                paperOrder = orderLifecycleService.transition(
                        paperOrder.getId(),
                        OrderState.REJECTED,
                        "LINKED_EXECUTION_FAILURE: LIVE leg failed - " + failureReason
                );

                // Release capital reservation for PAPER
                capitalReservationService.releaseByOrder(paperOrder.getId(), "LIVE_EXECUTION_FAILED");

                executionTraceService.trace(paperOrder,
                        com.stokr.oms.domain.ExecutionEventType.EXECUTION_REJECTED,
                        java.util.Map.of(
                                "phase", "LINKED_EXECUTION",
                                "reason", "LIVE leg failed: " + failureReason
                        )
                );

                log.warn("linked_execution.paper_rejected_due_to_live_failure liveOrderId={} paperOrderId={} reason={}",
                        liveOrder.getId(), paperOrder.getId(), failureReason);
            }
        } catch (Exception ex) {
            log.error("linked_execution.record_failure.error liveOrderId={} pairedId={} error={}",
                    liveOrder.getId(), pairedOrderId, ex.getMessage(), ex);
        }
    }

    /**
     * Check if this is a gated PAPER order (waiting for LIVE to succeed).
     */
    public boolean isPaperGatedByLive(OmsOrder order) {
        return order != null &&
               order.getExecutionMode() == ExecutionMode.PAPER &&
               "GATED_BY_LIVE".equals(order.getExecutionLinkage());
    }

    /**
     * Check if LIVE order gates PAPER execution.
     */
    public boolean isLiveGatingPaper(OmsOrder order) {
        return order != null &&
               order.getExecutionMode() == ExecutionMode.LIVE &&
               "SYNC_PAPER".equals(order.getExecutionLinkage());
    }
}
