package com.stokr.risk.rules;

import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Order(30)
public class MaxQuantityRule implements RiskRule {

    @Value("${stokr.risk.max-order-qty:100000}")
    private BigDecimal maxQty;

    @Override
    public String code() {
        return "MAX_QTY";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getQuantity().compareTo(maxQty) > 0) {
            return RiskDecision.reject(code(), "Quantity exceeds maximum");
        }
        return RiskDecision.ok();
    }
}
