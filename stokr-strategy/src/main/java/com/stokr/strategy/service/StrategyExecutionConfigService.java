package com.stokr.strategy.service;

import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.dto.StrategyExecutionConfigDto;
import com.stokr.strategy.dto.StrategyExecutionConfigRequest;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategyExecutionConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyExecutionConfigService {

    private final StrategyExecutionConfigRepository configRepository;
    private final StrategyDefinitionRepository strategyDefinitionRepository;

    public Optional<StrategyExecutionConfig> getByStrategyKey(String strategyKey) {
        return configRepository.findByStrategyKeyAndDeletedFalse(strategyKey);
    }

    /**
     * Returns existing config for the strategy key, or builds one with production-safe defaults
     * (force_fixed_qty=true, fixed_qty=1, live_enabled=false, execution_mode=PAPER).
     * Does NOT persist — caller decides whether to save.
     */
    public StrategyExecutionConfig getOrCreateDefault(String strategyKey) {
        return configRepository.findByStrategyKeyAndDeletedFalse(strategyKey)
                .orElseGet(() -> {
                    StrategyExecutionConfig cfg = new StrategyExecutionConfig();
                    cfg.setStrategyKey(strategyKey);
                    return cfg;
                });
    }

    public List<StrategyExecutionConfigDto> listAll() {
        return configRepository.findAllByDeletedFalseOrderByStrategyKeyAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    public StrategyExecutionConfigDto getById(UUID id) {
        return toDto(findOrThrow(id));
    }

    @Transactional
    public StrategyExecutionConfigDto create(StrategyExecutionConfigRequest req) {
        configRepository.findByStrategyKeyAndDeletedFalse(req.strategyKey()).ifPresent(existing -> {
            throw new IllegalArgumentException("Config already exists for strategy key: " + req.strategyKey());
        });

        StrategyExecutionConfig cfg = new StrategyExecutionConfig();
        applyRequest(cfg, req);
        cfg = configRepository.save(cfg);
        log.info("execution_config.created strategyKey={} id={}", cfg.getStrategyKey(), cfg.getId());
        return toDto(cfg);
    }

    @Transactional
    public StrategyExecutionConfigDto update(UUID id, StrategyExecutionConfigRequest req) {
        StrategyExecutionConfig cfg = findOrThrow(id);
        applyRequest(cfg, req);
        cfg = configRepository.save(cfg);
        log.info("execution_config.updated strategyKey={} id={}", cfg.getStrategyKey(), id);
        return toDto(cfg);
    }

    @Transactional
    public StrategyExecutionConfigDto patch(UUID id, StrategyExecutionConfigRequest req) {
        return update(id, req);
    }

    @Transactional
    public void delete(UUID id) {
        StrategyExecutionConfig cfg = findOrThrow(id);
        cfg.setDeleted(true);
        configRepository.save(cfg);
        log.info("execution_config.deleted strategyKey={} id={}", cfg.getStrategyKey(), id);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void applyRequest(StrategyExecutionConfig cfg, StrategyExecutionConfigRequest req) {
        if (req.strategyId() != null) {
            StrategyDefinition def = strategyDefinitionRepository.findById(req.strategyId())
                    .orElseThrow(() -> new NotFoundException("StrategyDefinition not found: " + req.strategyId()));
            cfg.setStrategy(def);
        }
        cfg.setStrategyKey(req.strategyKey());
        cfg.setEnabled(req.enabled());
        cfg.setExecutionMode(req.executionMode());
        cfg.setLiveEnabled(req.liveEnabled());
        cfg.setPaperEnabled(req.paperEnabled());
        cfg.setTelegramEnabled(req.telegramEnabled());
        cfg.setAllocatedCapital(req.allocatedCapital());
        cfg.setMaxPositions(req.maxPositions());
        cfg.setMaxTradeQuantity(req.maxTradeQuantity());
        cfg.setForceFixedQty(req.forceFixedQty());
        cfg.setFixedQty(req.fixedQty());
        cfg.setDailyLossLimit(req.dailyLossLimit());
        cfg.setCooldownMinutes(req.cooldownMinutes());
        cfg.setAllowPyramiding(req.allowPyramiding());
        cfg.setEmergencyStopEnabled(req.emergencyStopEnabled());
        cfg.setAutoDisableOnLoss(req.autoDisableOnLoss());
        cfg.setLiveConfirmationRequired(req.liveConfirmationRequired());
    }

    public StrategyExecutionConfigDto toDto(StrategyExecutionConfig cfg) {
        return new StrategyExecutionConfigDto(
                cfg.getId(),
                cfg.getCreatedAt(),
                cfg.getUpdatedAt(),
                cfg.getStrategy() != null ? cfg.getStrategy().getId() : null,
                cfg.getStrategyKey(),
                cfg.isEnabled(),
                cfg.getExecutionMode(),
                cfg.isLiveEnabled(),
                cfg.isPaperEnabled(),
                cfg.isTelegramEnabled(),
                cfg.getAllocatedCapital(),
                cfg.getMaxPositions(),
                cfg.getMaxTradeQuantity(),
                cfg.isForceFixedQty(),
                cfg.getFixedQty(),
                cfg.getDailyLossLimit(),
                cfg.getCooldownMinutes(),
                cfg.isAllowPyramiding(),
                cfg.isEmergencyStopEnabled(),
                cfg.isAutoDisableOnLoss(),
                cfg.isLiveConfirmationRequired()
        );
    }

    private StrategyExecutionConfig findOrThrow(UUID id) {
        return configRepository.findById(id)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new NotFoundException("StrategyExecutionConfig not found: " + id));
    }
}
