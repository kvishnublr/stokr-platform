package com.stokr.execution.sizing;

import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PositionSizingService {

    private final StrategyExecutionConfigService strategyExecutionConfigService;

    @Value("${stokr.execution.signal-order-quantity:1}")
    private BigDecimal signalOrderQuantity;

    /**
     * Resolves order quantity when converting a persisted strategy signal into an OMS order.
     * <p>
     * Production trader signals always use {@link #signalOrderQuantity} (default 1), regardless of
     * strategy execution config capital sizing or {@code suggestedQty} on the signal entity.
     * Test-lab signals ({@code testTrade=true}) honour {@code suggestedQty} so admin scenarios
     * can still vary quantity; broker sample trades on /brokers do not use this service.
     */
    public BigDecimal resolveQuantity(
            String strategyKey,
            UUID userId,
            BigDecimal suggestedQty,
            BigDecimal marketPrice,
            boolean testTrade) {
        if (Boolean.TRUE.equals(testTrade)) {
            return resolveTestTradeQuantity(strategyKey, userId, suggestedQty, marketPrice);
        }
        if (suggestedQty != null && suggestedQty.compareTo(signalOrderQuantity) != 0) {
            log.debug("sizing.signal_fixed strategyKey={} suggested={} applied={}",
                    strategyKey, suggestedQty, signalOrderQuantity);
        }
        return signalOrderQuantity;
    }

    private BigDecimal resolveTestTradeQuantity(
            String strategyKey,
            UUID userId,
            BigDecimal suggestedQty,
            BigDecimal marketPrice) {
        Optional<StrategyExecutionConfig> opt = userId != null
                ? strategyExecutionConfigService.getByStrategyKeyForUser(userId, strategyKey)
                : strategyExecutionConfigService.getByStrategyKey(strategyKey);
        if (opt.isEmpty()) {
            return suggestedQty != null && suggestedQty.signum() > 0 ? suggestedQty : BigDecimal.ONE;
        }

        StrategyExecutionConfig cfg = opt.get();

        if (cfg.isForceFixedQty()) {
            return cfg.getFixedQty();
        }

        if (cfg.getAllocatedCapital() != null
                && cfg.getMaxPositions() > 0
                && marketPrice != null
                && marketPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal perPosition = cfg.getAllocatedCapital()
                    .divide(BigDecimal.valueOf(cfg.getMaxPositions()), 8, RoundingMode.FLOOR);
            BigDecimal qty = perPosition.divide(marketPrice, 0, RoundingMode.FLOOR);
            return qty.max(BigDecimal.ONE);
        }

        return suggestedQty != null && suggestedQty.signum() > 0 ? suggestedQty : BigDecimal.ONE;
    }
}
