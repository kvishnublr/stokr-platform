package com.stokr.risk.rules;

import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.risk.service.StrategyToggleService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(50)
@RequiredArgsConstructor
public class StrategyEnabledRule implements RiskRule {

    private final StrategyToggleService strategyToggleService;

    @Override
    public String code() {
        return "STRATEGY_DISABLED";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        String key = context.order().getStrategyKey();
        if (key == null) {
            return RiskDecision.ok();
        }
        if (!strategyToggleService.isEnabled(key)) {
            return RiskDecision.reject(code(), "Strategy disabled");
        }
        return RiskDecision.ok();
    }
}
