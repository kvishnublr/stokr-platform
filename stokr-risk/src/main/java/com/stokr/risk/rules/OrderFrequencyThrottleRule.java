package com.stokr.risk.rules;

import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rolling per-minute order cap using orders created in the last 60s (excluding backtests). 0 disables.
 */
@Component
@Order(38)
public class OrderFrequencyThrottleRule implements RiskRule {

    @Value("${stokr.risk.max-orders-per-minute:0}")
    private int maxOrdersPerMinute;

    @Override
    public String code() {
        return "ORDER_FREQUENCY";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getBacktestRunId() != null) {
            return RiskDecision.ok();
        }
        if (maxOrdersPerMinute <= 0) {
            return RiskDecision.ok();
        }
        if (context.ordersCreatedLast60Seconds() >= maxOrdersPerMinute) {
            return RiskDecision.reject(code(), "Order frequency limit exceeded");
        }
        return RiskDecision.ok();
    }
}
