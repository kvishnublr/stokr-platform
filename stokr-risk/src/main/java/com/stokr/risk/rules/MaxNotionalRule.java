package com.stokr.risk.rules;

import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Order(35)
public class MaxNotionalRule implements RiskRule {

    @Value("${stokr.risk.max-order-notional:50000000}")
    private BigDecimal maxNotional;

    @Override
    public String code() {
        return "MAX_NOTIONAL";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        BigDecimal ref = context.order().getEntryReferencePrice();
        if (ref == null) {
            ref = context.order().getLimitPrice();
        }
        if (ref == null) {
            ref = BigDecimal.ZERO;
        }
        BigDecimal q = context.order().getQuantity();
        if (q == null) {
            return RiskDecision.ok();
        }
        BigDecimal notional = ref.multiply(q).abs();
        if (notional.compareTo(maxNotional) > 0) {
            return RiskDecision.reject(code(), "Order notional exceeds maximum");
        }
        return RiskDecision.ok();
    }
}
