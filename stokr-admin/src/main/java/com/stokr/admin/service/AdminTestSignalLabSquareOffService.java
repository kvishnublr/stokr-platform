package com.stokr.admin.service;

import com.stokr.admin.domain.AdminTestSignalRun;
import com.stokr.admin.repository.AdminTestSignalRunRepository;
import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.execution.service.ExecutionService;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.risk.service.RiskEngineService;
import com.stokr.execution.risk.RiskContextFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Square-off helper for Test Signal Lab — supports synchronous immediate exit (LIVE MIS)
 * and scheduled fallback via {@link AdminTestSignalLabSquareOffScheduler}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTestSignalLabSquareOffService {

    private static final String TEST_LAB_PRODUCT = "MIS";

    private final AdminTestSignalRunRepository runRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final ExecutionService executionService;
    private final RiskEngineService riskEngineService;
    private final RiskContextFactory riskContextFactory;

    @Value("${stokr.risk.zone:Asia/Kolkata}")
    private String riskZone;

    /**
     * Places opposite MARKET MIS exit for a test-lab entry order. When {@code synchronous} is true,
     * broker submission runs inline (no Rabbit hop) for sub-second round-trip.
     */
    @Transactional
    public Optional<UUID> squareOffImmediately(AdminTestSignalRun run, OmsOrder entryOrder, boolean synchronous) {
        if (run == null || entryOrder == null) {
            return Optional.empty();
        }
        if (run.getSquareOffStatus() != null && "COMPLETED".equalsIgnoreCase(run.getSquareOffStatus())) {
            return Optional.ofNullable(run.getSquareOffOrderId());
        }

        String exitSide = "BUY".equalsIgnoreCase(entryOrder.getSide()) ? "SELL" : "BUY";
        BigDecimal qty = entryOrder.getQuantity() != null && entryOrder.getQuantity().signum() > 0
                ? entryOrder.getQuantity()
                : (run.getQuantity() != null ? run.getQuantity() : BigDecimal.ONE);
        String symbol = AdminTestSignalLabSymbol.normalize(run.getSymbol(), run.getExchange());
        ExecutionMode mode = "LIVE".equalsIgnoreCase(run.getExecutionMode()) ? ExecutionMode.LIVE : ExecutionMode.PAPER;
        String broker = mode == ExecutionMode.LIVE ? "ZERODHA" : "SIM";
        String idempotencyKey = "test-squareoff:" + run.getId() + ":" + exitSide;

        OmsOrder draft = new OmsOrder();
        draft.setSymbol(symbol);
        draft.setSide(exitSide);
        draft.setOrderType("MARKET");
        draft.setQuantity(qty);
        draft.setStrategyKey(run.getStrategyKey());
        // Exit leg must not reuse entry signal_id — ux_oms_orders_user_signal_live allows one order per signal.
        draft.setSignalId(null);
        draft.setExecutionMode(mode);
        draft.setBrokerVendor(broker);
        draft.setTestTrade(true);
        draft.setTestRunId(run.getId());

        OmsOrder exit = orderLifecycleService.createOrGetIdempotent(run.getTraderUserId(), idempotencyKey, draft);
        if (exit.getState() != OrderState.CREATED) {
            run.setSquareOffOrderId(exit.getId());
            run.setSquareOffStatus("COMPLETED");
            run.setSquareOffCompletedAt(Instant.now());
            runRepository.save(run);
            return Optional.of(exit.getId());
        }

        exit = orderLifecycleService.transition(exit.getId(), OrderState.VALIDATED, null);
        exit = orderLifecycleService.transition(exit.getId(), OrderState.RISK_CHECK, null);

        ZoneId zone = ZoneId.of(riskZone);
        RiskContext ctx = riskContextFactory.build(run.getTraderUserId(), exit, zone, Instant.now(), null);
        RiskDecision decision = riskEngineService.evaluate(ctx);
        if (!decision.allowed()) {
            log.warn("test.squareoff.risk_blocked runId={} reason={}", run.getId(), decision.message());
            run.setSquareOffStatus("RISK_BLOCKED");
            runRepository.save(run);
            return Optional.empty();
        }

        exit = orderLifecycleService.transition(exit.getId(), OrderState.PENDING_SUBMISSION, null);
        executionService.dispatch(
                new ExecutionDispatchMessage(
                        exit.getId(),
                        run.getTraderUserId(),
                        run.getSignalId(),
                        broker,
                        0,
                        null,
                        mode.name(),
                        exit.getId().getMostSignificantBits() ^ exit.getId().getLeastSignificantBits(),
                        Instant.now()
                ),
                synchronous
        );

        exit = omsOrderRepository.findById(exit.getId()).orElse(exit);
        boolean brokerOk = exit.getExecutionMode() == ExecutionMode.PAPER
                ? (exit.getState() == OrderState.FILLED || exit.getState() == OrderState.ACCEPTED)
                : (exit.getBrokerExternalOrderId() != null && !exit.getBrokerExternalOrderId().isBlank())
                        && (exit.getState() == OrderState.SUBMITTED
                        || exit.getState() == OrderState.ACCEPTED
                        || exit.getState() == OrderState.FILLED
                        || exit.getState() == OrderState.PARTIALLY_FILLED);
        run.setSquareOffOrderId(exit.getId());
        run.setSquareOffStatus(brokerOk ? "COMPLETED" : "FAILED");
        run.setSquareOffCompletedAt(Instant.now());
        runRepository.save(run);
        log.info("test.squareoff.immediate runId={} exitOrderId={} state={} product={}",
                run.getId(), exit.getId(), exit.getState(), TEST_LAB_PRODUCT);
        return Optional.of(exit.getId());
    }
}
