package com.stokr.risk;

import org.springframework.stereotype.Component;

@Component
public class CapitalLimitRule implements RiskRule {

    @Override
    public String getRuleName() {
        return "CAPITAL_LIMIT";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.totalDeployedCapital().compareTo(context.availableCapital()) > 0) {
            return RiskDecision.fail(String.format(
                    "Total deployed capital %.2f exceeds available capital %.2f",
                    context.totalDeployedCapital(), context.availableCapital()));
        }
        return RiskDecision.pass();
    }
}
