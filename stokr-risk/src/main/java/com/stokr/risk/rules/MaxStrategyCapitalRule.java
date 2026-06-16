package com.stokr.risk.rules;

import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Caps total open notional tied up in working orders for a single strategy key. 0 disables.
 */
@Component
@Order(33)
@RequiredArgsConstructor
public class MaxStrategyCapitalRule implements RiskRule {

    private static final List<OrderState> OPEN_RISK_STATES = List.of(
            OrderState.CREATED,
            OrderState.VALIDATED,
            OrderState.RISK_CHECK,
            OrderState.PENDING_SUBMISSION,
            OrderState.SUBMITTED,
            OrderState.ACCEPTED,
            OrderState.PARTIALLY_FILLED
    );

    private final OmsOrderRepository omsOrderRepository;

    @Value("${stokr.risk.max-strategy-notional:0}")
    private BigDecimal maxStrategyNotional;

    @Override
    public String code() {
        return "MAX_STRATEGY_NOTIONAL";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        OmsOrder o = context.order();
        if (o.getBacktestRunId() != null) {
            return RiskDecision.ok();
        }
        if (o.isSimulation()) {
            return RiskDecision.ok();
        }
        if (maxStrategyNotional == null || maxStrategyNotional.compareTo(BigDecimal.ZERO) <= 0) {
            return RiskDecision.ok();
        }
        if (o.getStrategyKey() == null || o.getStrategyKey().isBlank()) {
            return RiskDecision.ok();
        }
        BigDecimal existing = omsOrderRepository.sumOpenNotionalExcluding(
                context.userId(),
                o.getStrategyKey(),
                o.getId(),
                OPEN_RISK_STATES
        );
        BigDecimal px = o.getLimitPrice() != null ? o.getLimitPrice()
                : (o.getEntryReferencePrice() != null ? o.getEntryReferencePrice() : BigDecimal.ZERO);
        BigDecimal add = o.getQuantity() != null ? o.getQuantity().multiply(px) : BigDecimal.ZERO;
        BigDecimal total = existing.add(add).setScale(8, RoundingMode.HALF_UP);
        if (total.compareTo(maxStrategyNotional) > 0) {
            return RiskDecision.reject(code(), "Max strategy notional allocation exceeded");
        }
        return RiskDecision.ok();
    }
}
