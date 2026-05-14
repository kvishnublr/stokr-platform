package com.stokr.risk.rules;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Halts LIVE and PAPER entries when ATR/close ratio from the signal envelope exceeds threshold. 0 disables.
 */
@Component
@Order(42)
public class VolatilityHaltRule implements RiskRule {

    @Value("${stokr.risk.volatility-halt-atr-close-ratio:0}")
    private BigDecimal volatilityHaltAtrCloseRatio;

    @Override
    public String code() {
        return "VOLATILITY_HALT";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getBacktestRunId() != null) {
            return RiskDecision.ok();
        }
        ExecutionMode mode = context.order().getExecutionMode();
        if (mode != ExecutionMode.LIVE && mode != ExecutionMode.PAPER) {
            return RiskDecision.ok();
        }
        if (volatilityHaltAtrCloseRatio == null || volatilityHaltAtrCloseRatio.compareTo(BigDecimal.ZERO) <= 0) {
            return RiskDecision.ok();
        }
        BigDecimal ratio = context.signalAtrToCloseRatio();
        if (ratio == null) {
            return RiskDecision.ok();
        }
        if (ratio.compareTo(volatilityHaltAtrCloseRatio) > 0) {
            return RiskDecision.reject(code(), "Volatility halt — ATR/price ratio too high");
        }
        return RiskDecision.ok();
    }
}
