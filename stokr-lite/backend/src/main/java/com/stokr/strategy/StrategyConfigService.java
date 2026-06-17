package com.stokr.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StrategyConfigService {

    private final StrategyConfigRepository configRepository;

    public List<StrategyConfig> listAll() {
        return configRepository.findAll();
    }

    public StrategyConfig getByStrategyId(Long strategyId) {
        return configRepository.findByStrategyId(strategyId)
                .orElseGet(() -> ensureDefault(strategyId));
    }

    @Transactional
    public StrategyConfig ensureDefault(Long strategyId) {
        return configRepository.findByStrategyId(strategyId)
                .orElseGet(() -> configRepository.save(
                        StrategyConfig.builder().strategyId(strategyId).build()
                ));
    }

    @Transactional
    public StrategyConfig update(Long strategyId, StrategyConfig patch) {
        StrategyConfig cfg = getByStrategyId(strategyId);
        if (patch.getAllocatedCapital() != null) cfg.setAllocatedCapital(patch.getAllocatedCapital());
        if (patch.getMaxPositions() != null) cfg.setMaxPositions(patch.getMaxPositions());
        if (patch.getMaxTradeQuantity() != null) cfg.setMaxTradeQuantity(patch.getMaxTradeQuantity());
        cfg.setForceFixedQty(patch.isForceFixedQty());
        if (patch.getFixedQty() != null) cfg.setFixedQty(patch.getFixedQty());
        if (patch.getSizingMode() != null) cfg.setSizingMode(patch.getSizingMode());
        if (patch.getMaxCapitalPerTrade() != null) cfg.setMaxCapitalPerTrade(patch.getMaxCapitalPerTrade());
        if (patch.getMaxRiskPerTradePct() != null) cfg.setMaxRiskPerTradePct(patch.getMaxRiskPerTradePct());
        if (patch.getDailyLossLimit() != null) cfg.setDailyLossLimit(patch.getDailyLossLimit());
        if (patch.getCooldownMinutes() != null) cfg.setCooldownMinutes(patch.getCooldownMinutes());
        cfg.setLiveEnabled(patch.isLiveEnabled());
        cfg.setPaperEnabled(patch.isPaperEnabled());
        cfg.setEnabled(patch.isEnabled());
        return configRepository.save(cfg);
    }
}
