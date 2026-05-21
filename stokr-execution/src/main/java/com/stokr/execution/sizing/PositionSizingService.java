package com.stokr.execution.sizing;

import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * Resolves order quantity for a strategy signal.
     * Uses trader's personal config if it exists, falls back to global admin config.
     * Priority: forceFixedQty → capital-based sizing → suggestedQty fallback → 1
     */
    public BigDecimal resolveQuantity(String strategyKey, UUID userId, BigDecimal suggestedQty, BigDecimal marketPrice) {
        Optional<StrategyExecutionConfig> opt = userId != null
                ? strategyExecutionConfigService.getByStrategyKeyForUser(userId, strategyKey)
                : strategyExecutionConfigService.getByStrategyKey(strategyKey);
        if (opt.isEmpty()) {
            log.debug("sizing.no_config strategyKey={} fallback=suggested", strategyKey);
            return suggestedQty != null ? suggestedQty : BigDecimal.ONE;
        }

        StrategyExecutionConfig cfg = opt.get();

        if (cfg.isForceFixedQty()) {
            log.debug("sizing.fixed strategyKey={} qty={}", strategyKey, cfg.getFixedQty());
            return cfg.getFixedQty();
        }

        if (cfg.getAllocatedCapital() != null
                && cfg.getMaxPositions() > 0
                && marketPrice != null
                && marketPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal perPosition = cfg.getAllocatedCapital()
                    .divide(BigDecimal.valueOf(cfg.getMaxPositions()), 8, RoundingMode.FLOOR);
            BigDecimal qty = perPosition.divide(marketPrice, 0, RoundingMode.FLOOR);
            BigDecimal result = qty.max(BigDecimal.ONE);
            log.debug("sizing.capital strategyKey={} allocated={} maxPos={} price={} qty={}",
                    strategyKey, cfg.getAllocatedCapital(), cfg.getMaxPositions(), marketPrice, result);
            return result;
        }

        BigDecimal fallback = suggestedQty != null ? suggestedQty : BigDecimal.ONE;
        log.debug("sizing.fallback strategyKey={} qty={}", strategyKey, fallback);
        return fallback;
    }
}
