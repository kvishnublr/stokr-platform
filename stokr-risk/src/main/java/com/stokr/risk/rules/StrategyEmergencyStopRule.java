package com.stokr.risk.rules;

import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Hard stop: rejects all orders for a strategy when emergencyStopEnabled=true.
 */
@Component
@Order(58)
public class StrategyEmergencyStopRule implements RiskRule {

    @Override
    public String code() {
        return "STRATEGY_EMERGENCY_STOP";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getBacktestRunId() != null) return RiskDecision.ok();
        StrategyExecutionConfig cfg = context.strategyExecutionConfig();
        if (cfg == null) return RiskDecision.ok();
        if (cfg.isEmergencyStopEnabled()) {
            return RiskDecision.reject(code(), "Emergency stop is active for strategy: " + cfg.getStrategyKey());
        }
        return RiskDecision.ok();
    }
}
