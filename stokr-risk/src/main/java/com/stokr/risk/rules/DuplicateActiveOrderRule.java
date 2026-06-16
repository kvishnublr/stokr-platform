package com.stokr.risk.rules;

import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Prevents stacking duplicate directional orders while prior orders are still working the lifecycle.
 */
@Component
@Order(36)
@RequiredArgsConstructor
public class DuplicateActiveOrderRule implements RiskRule {

    private static final List<OrderState> DUPLICATE_STATES = List.of(
            OrderState.CREATED,
            OrderState.VALIDATED,
            OrderState.RISK_CHECK,
            OrderState.PENDING_SUBMISSION,
            OrderState.SUBMITTED,
            OrderState.ACCEPTED,
            OrderState.PARTIALLY_FILLED
    );

    private final OmsOrderRepository omsOrderRepository;
    private final StrategySignalRepository signalRepository;

    @Override
    public String code() {
        return "DUPLICATE_ACTIVE_ORDER";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        OmsOrder o = context.order();
        if (o.getBacktestRunId() != null) {
            return RiskDecision.ok();
        }
        if (o.getSymbol() == null || o.getSide() == null || o.getExecutionMode() == null) {
            return RiskDecision.ok();
        }
        if (isExitBypass(o)) {
            return RiskDecision.ok();
        }

        // OPTION 3: Exits always execute - never block exits due to pending entries
        // This ensures position management (exits) is never blocked by position entry state
        if (o.getSignalId() != null) {
            Optional<StrategySignalEntity> signalOpt = signalRepository.findById(o.getSignalId());
            if (signalOpt.isPresent()) {
                SignalType signalType = signalOpt.get().getSignalType();
                if (signalType == SignalType.EXIT) {
                    return RiskDecision.ok(); // Exits bypass duplicate checks
                }
            }
        }

        long n = omsOrderRepository.countActiveSameDirection(
                context.userId(),
                o.getSymbol(),
                o.getSide(),
                o.getExecutionMode(),
                o.getId(),
                DUPLICATE_STATES
        );
        if (n > 0) {
            return RiskDecision.reject(code(), "An active order already exists for this symbol and side");
        }
        return RiskDecision.ok();
    }

    private static boolean isExitBypass(OmsOrder o) {
        if (o.getStrategyKey() != null) {
            String sk = o.getStrategyKey().trim().toUpperCase(Locale.ROOT);
            if (sk.startsWith("TERMINAL_")) {
                return true;
            }
        }
        String idem = o.getIdempotencyKey();
        if (idem != null) {
            String key = idem.trim().toLowerCase(Locale.ROOT);
            if (key.startsWith("outcome-exit:") || key.startsWith("terminal:flatten:")
                    || key.startsWith("terminal:exit:")) {
                return true;
            }
        }
        return false;
    }
}
