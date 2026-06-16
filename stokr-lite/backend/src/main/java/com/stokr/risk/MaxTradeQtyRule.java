package com.stokr.risk;

import org.springframework.stereotype.Component;

@Component
public class MaxTradeQtyRule implements RiskRule {

    @Override
    public String getRuleName() {
        return "MAX_TRADE_QTY";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.orderQuantity() > context.maxQtyPerTrade()) {
            return RiskDecision.fail(String.format(
                    "Order qty %d exceeds limit %d",
                    context.orderQuantity(), context.maxQtyPerTrade()));
        }
        return RiskDecision.pass();
    }
}
