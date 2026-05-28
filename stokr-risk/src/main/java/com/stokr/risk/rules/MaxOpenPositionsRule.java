package com.stokr.risk.rules;

import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@Order(37)
@RequiredArgsConstructor
public class MaxOpenPositionsRule implements RiskRule {

    private final PortfolioPositionRepository portfolioPositionRepository;

    @Value("${stokr.risk.max-open-positions:100}")
    private int maxOpenPositions;

    @Override
    public String code() {
        return "MAX_OPEN_POSITIONS";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order() != null && context.order().isTestTrade()) {
            return RiskDecision.ok();
        }
        // PAPER orders are not limited by max open positions — only LIVE orders are capped
        if (context.order() != null && context.order().getExecutionMode() != null
                && "PAPER".equalsIgnoreCase(context.order().getExecutionMode().name())) {
            return RiskDecision.ok();
        }
        List<PortfolioPosition> list = portfolioPositionRepository.findByUserIdAndDeletedFalse(context.userId());
        long open = list.stream()
                .filter(p -> p.getQuantity() != null && p.getQuantity().abs().compareTo(BigDecimal.ZERO) > 0)
                .count();
        if (open >= maxOpenPositions) {
            String held = list.stream()
                    .filter(p -> p.getQuantity() != null && p.getQuantity().abs().compareTo(BigDecimal.ZERO) > 0)
                    .map(PortfolioPosition::getSymbol)
                    .findFirst()
                    .orElse("?");
            String msg = maxOpenPositions <= 1
                    ? "Pilot: one stock at a time — holding " + held + ", close before new entries"
                    : "Max open positions reached (" + open + "/" + maxOpenPositions + ")";
            return RiskDecision.reject(code(), msg);
        }
        return RiskDecision.ok();
    }
}
