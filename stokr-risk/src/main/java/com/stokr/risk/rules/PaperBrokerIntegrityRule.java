package com.stokr.risk.rules;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * PAPER mode must never be routed to a live broker adapter.
 */
@Component
@Order(16)
public class PaperBrokerIntegrityRule implements RiskRule {

    @Override
    public String code() {
        return "PAPER_BROKER_INTEGRITY";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getExecutionMode() != ExecutionMode.PAPER) {
            return RiskDecision.ok();
        }
        String broker = context.order().getBrokerVendor();
        if (broker == null || broker.isBlank()) {
            return RiskDecision.reject(code(), "Paper orders require an explicit simulated broker vendor");
        }
        if (!"SIM".equalsIgnoreCase(broker)) {
            return RiskDecision.reject(code(), "Paper execution must use SIM broker channel");
        }
        return RiskDecision.ok();
    }
}
