package com.stokr.risk.rules;

import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Rejects orders when the strategy's today realized PnL has breached its configured daily loss limit.
 */
@Component
@Order(52)
public class StrategyDailyLossLimitRule implements RiskRule {

    @Override
    public String code() {
        return "STRATEGY_DAILY_LOSS_LIMIT";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getBacktestRunId() != null) return RiskDecision.ok();
        StrategyExecutionConfig cfg = context.strategyExecutionConfig();
        if (cfg == null || cfg.getDailyLossLimit() == null) return RiskDecision.ok();
        BigDecimal todayPnl = context.strategyDailyPnl() != null ? context.strategyDailyPnl() : BigDecimal.ZERO;
        BigDecimal floor = cfg.getDailyLossLimit().negate();
        if (todayPnl.compareTo(floor) < 0) {
            return RiskDecision.reject(code(),
                    "Strategy daily loss limit breached: pnl=" + todayPnl + " limit=" + cfg.getDailyLossLimit());
        }
        return RiskDecision.ok();
    }
}
