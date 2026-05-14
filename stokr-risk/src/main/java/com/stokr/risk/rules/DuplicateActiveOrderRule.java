package com.stokr.risk.rules;

import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

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
        if (o.getSymbol() == null || o.getSide() == null) {
            return RiskDecision.ok();
        }
        long n = omsOrderRepository.countActiveSameDirection(
                context.userId(),
                o.getSymbol(),
                o.getSide(),
                o.getId(),
                DUPLICATE_STATES
        );
        if (n > 0) {
            return RiskDecision.reject(code(), "An active order already exists for this symbol and side");
        }
        return RiskDecision.ok();
    }
}
