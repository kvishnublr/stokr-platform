package com.stokr.risk.rules;

import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Minimum spacing between LIVE orders per user/symbol (wall clock). Skipped for backtest/replay runs.
 */
@Component
@Order(40)
@RequiredArgsConstructor
public class OrderCooldownRule implements RiskRule {

    private final OmsOrderRepository omsOrderRepository;

    @Value("${stokr.risk.order-cooldown-ms:0}")
    private long cooldownMs;

    @Override
    public String code() {
        return "ORDER_COOLDOWN";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (cooldownMs <= 0) {
            return RiskDecision.ok();
        }
        if (context.order().getBacktestRunId() != null) {
            return RiskDecision.ok();
        }
        Optional<Instant> last = omsOrderRepository.findLatestCreatedAtForUserSymbolExcluding(
                context.userId(),
                context.order().getSymbol(),
                context.order().getId()
        );
        if (last.isEmpty()) {
            return RiskDecision.ok();
        }
        long gap = Instant.now().toEpochMilli() - last.get().toEpochMilli();
        if (gap < cooldownMs) {
            return RiskDecision.reject(code(), "Order cooldown active");
        }
        return RiskDecision.ok();
    }
}
