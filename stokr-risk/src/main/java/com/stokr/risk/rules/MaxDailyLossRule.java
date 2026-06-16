package com.stokr.risk.rules;

import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Rejects new risk checks when today's MTM is beyond configured max loss (negative PnL bound).
 * {@code stokr.risk.max-daily-loss-amount} is a positive currency amount; 0 disables the rule.
 */
@Component
@Order(22)
public class MaxDailyLossRule implements RiskRule {

    @Value("${stokr.risk.max-daily-loss-amount:0}")
    private BigDecimal maxDailyLossAmount;

    @Override
    public String code() {
        return "MAX_DAILY_LOSS";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getBacktestRunId() != null) {
            return RiskDecision.ok();
        }
        // PAPER orders bypass daily loss limit ??? only enforce for LIVE
        if (context.order().getExecutionMode() != null
                && "PAPER".equalsIgnoreCase(context.order().getExecutionMode().name())) {
            return RiskDecision.ok();
        }
        if (maxDailyLossAmount == null || maxDailyLossAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return RiskDecision.ok();
        }
        BigDecimal today = context.dayPnl() != null ? context.dayPnl() : BigDecimal.ZERO;
        BigDecimal floor = maxDailyLossAmount.negate();
        if (today.compareTo(floor) < 0) {
            return RiskDecision.reject(code(), "Max daily loss exceeded");
        }
        return RiskDecision.ok();
    }
}
