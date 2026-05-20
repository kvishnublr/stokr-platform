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
 * Rejects new orders when the strategy already has maxPositions open working orders.
 */
@Component
@Order(54)
@RequiredArgsConstructor
public class StrategyMaxPositionsRule implements RiskRule {

    private static final List<OrderState> OPEN_STATES = List.of(
            OrderState.CREATED, OrderState.VALIDATED, OrderState.RISK_CHECK,
            OrderState.PENDING_SUBMISSION, OrderState.SUBMITTED, OrderState.ACCEPTED,
            OrderState.PARTIALLY_FILLED
    );

    private final OmsOrderRepository omsOrderRepository;

    @Override
    public String code() {
        return "STRATEGY_MAX_POSITIONS";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getBacktestRunId() != null) return RiskDecision.ok();
        StrategyExecutionConfig cfg = context.strategyExecutionConfig();
        if (cfg == null || cfg.getMaxPositions() <= 0) return RiskDecision.ok();
        String strategyKey = context.order().getStrategyKey();
        if (strategyKey == null || strategyKey.isBlank()) return RiskDecision.ok();
        long open = omsOrderRepository.countOpenOrdersByStrategyKey(
                context.userId(), strategyKey, context.order().getId(), OPEN_STATES);
        if (open >= cfg.getMaxPositions()) {
            return RiskDecision.reject(code(),
                    "Strategy max positions reached: open=" + open + " max=" + cfg.getMaxPositions());
        }
        return RiskDecision.ok();
    }
}
