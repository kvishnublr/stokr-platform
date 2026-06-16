package com.stokr.risk;

import org.springframework.stereotype.Component;

@Component
public class MaxPositionsRule implements RiskRule {

    @Override
    public String getRuleName() {
        return "MAX_POSITIONS";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.currentOpenPositions() >= context.maxPositions()) {
            return RiskDecision.fail(String.format(
                    "Open positions %d >= limit %d",
                    context.currentOpenPositions(), context.maxPositions()));
        }
        return RiskDecision.pass();
    }
}
