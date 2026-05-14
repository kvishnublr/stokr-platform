package com.stokr.risk.rules;

import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Stops trading when portfolio drawdown (from recent equity snapshots) exceeds a percentage threshold.
 * {@code stokr.risk.max-portfolio-drawdown-pct} on 0–100 scale; 0 disables.
 */
@Component
@Order(24)
public class MaxPortfolioDrawdownStopRule implements RiskRule {

    @Value("${stokr.risk.max-portfolio-drawdown-pct:0}")
    private BigDecimal maxPortfolioDrawdownPct;

    @Override
    public String code() {
        return "MAX_PORTFOLIO_DRAWDOWN";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getBacktestRunId() != null) {
            return RiskDecision.ok();
        }
        if (maxPortfolioDrawdownPct == null || maxPortfolioDrawdownPct.compareTo(BigDecimal.ZERO) <= 0) {
            return RiskDecision.ok();
        }
        BigDecimal dd = context.portfolioDrawdownPct() != null ? context.portfolioDrawdownPct() : BigDecimal.ZERO;
        if (dd.compareTo(maxPortfolioDrawdownPct) > 0) {
            return RiskDecision.reject(code(), "Max portfolio drawdown exceeded");
        }
        return RiskDecision.ok();
    }
}
