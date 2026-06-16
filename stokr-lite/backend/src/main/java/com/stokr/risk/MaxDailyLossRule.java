package com.stokr.risk;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class MaxDailyLossRule implements RiskRule {

    @Override
    public String getRuleName() {
        return "MAX_DAILY_LOSS";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.dailyPnl().negate().compareTo(context.maxDailyLoss()) > 0) {
            return RiskDecision.fail(String.format(
                    "Daily loss %.2f exceeds limit %.2f",
                    context.dailyPnl().negate(), context.maxDailyLoss()));
        }
        return RiskDecision.pass();
    }
}
