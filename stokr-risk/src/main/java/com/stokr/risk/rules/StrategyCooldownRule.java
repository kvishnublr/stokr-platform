package com.stokr.risk.rules;

import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Rejects orders placed within cooldownMinutes of the last order for the same strategy.
 */
@Component
@Order(56)
@RequiredArgsConstructor
public class StrategyCooldownRule implements RiskRule {

    private final OmsOrderRepository omsOrderRepository;

    @Override
    public String code() {
        return "STRATEGY_COOLDOWN";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order().getBacktestRunId() != null) return RiskDecision.ok();
        StrategyExecutionConfig cfg = context.strategyExecutionConfig();
        if (cfg == null || cfg.getCooldownMinutes() <= 0) return RiskDecision.ok();
        String strategyKey = context.order().getStrategyKey();
        if (strategyKey == null || strategyKey.isBlank()) return RiskDecision.ok();

        Instant cooldownBoundary = Instant.ofEpochMilli(context.lastOrderEpochMs())
                .minusSeconds((long) cfg.getCooldownMinutes() * 60);

        return omsOrderRepository.findLatestCreatedAtForStrategyExcluding(
                        context.userId(), strategyKey, context.order().getId())
                .filter(last -> last.isAfter(cooldownBoundary))
                .map(last -> RiskDecision.reject(code(),
                        "Strategy cooldown active: last order at " + last + " cooldown=" + cfg.getCooldownMinutes() + "m"))
                .orElse(RiskDecision.ok());
    }
}
