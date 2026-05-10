package com.stokr.risk.rules;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.LiveTraderEligibilityResult;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.risk.service.LiveTradingTraderEligibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(15)
@RequiredArgsConstructor
public class LiveTradingEligibilityRule implements RiskRule {

    private final LiveTradingTraderEligibilityService traderEligibilityService;

    @Override
    public String code() {
        return "LIVE_TRADING_BLOCKED";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getExecutionMode() != ExecutionMode.LIVE) {
            return RiskDecision.ok();
        }
        LiveTraderEligibilityResult r = traderEligibilityService.evaluateForLiveOrder(
                context.userId(),
                context.order().getStrategyKey(),
                context.order().getBrokerVendor()
        );
        if (!r.allowed()) {
            return RiskDecision.reject(r.reasonCode() != null ? r.reasonCode() : code(), r.message());
        }
        return RiskDecision.ok();
    }
}
