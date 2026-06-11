package com.stokr.risk.rules;

import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Blocks adding to an open position in the same direction when pyramiding is disabled (default).
 */
@Component
@Order(60)
@RequiredArgsConstructor
public class PyramidingRule implements RiskRule {

    private static final List<OrderState> OPEN_STATES = List.of(
            OrderState.CREATED, OrderState.VALIDATED, OrderState.RISK_CHECK,
            OrderState.PENDING_SUBMISSION, OrderState.SUBMITTED, OrderState.ACCEPTED,
            OrderState.PARTIALLY_FILLED
    );

    private final OmsOrderRepository omsOrderRepository;

    @Override
    public String code() {
        return "PYRAMIDING_BLOCKED";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getBacktestRunId() != null) return RiskDecision.ok();
        StrategyExecutionConfig cfg = context.strategyExecutionConfig();
        if (cfg == null || cfg.isAllowPyramiding()) return RiskDecision.ok();
        String strategyKey = context.order().getStrategyKey();
        String symbol = context.order().getSymbol();
        String side = context.order().getSide();
        if (strategyKey == null || symbol == null || side == null) return RiskDecision.ok();

        long existing = omsOrderRepository.countActiveSameDirection(
                context.userId(), symbol, side, context.order().getExecutionMode(), context.order().getId(), OPEN_STATES);
        if (existing > 0) {
            return RiskDecision.reject(code(),
                    "Pyramiding blocked: existing open " + side + " order for " + symbol + " in strategy " + strategyKey);
        }
        return RiskDecision.ok();
    }
}
