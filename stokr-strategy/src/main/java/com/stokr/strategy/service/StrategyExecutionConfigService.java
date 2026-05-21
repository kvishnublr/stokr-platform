package com.stokr.strategy.service;

import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.dto.StrategyExecutionConfigDto;
import com.stokr.strategy.dto.StrategyExecutionConfigRequest;
import com.stokr.strategy.dto.TraderExecutionConfigDto;
import com.stokr.strategy.dto.TraderExecutionConfigPatchRequest;
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

    /** Global admin config only (user_id IS NULL). Used by admin operations and alert service. */
    public Optional<StrategyExecutionConfig> getByStrategyKey(String strategyKey) {
        return configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(strategyKey);
    }

    /**
     * Effective config for a trader: user-specific override first, falls back to global admin config.
     * Used by risk engine and position sizing so trader settings take precedence.
     */
    public Optional<StrategyExecutionConfig> getByStrategyKeyForUser(UUID userId, String strategyKey) {
        return configRepository.findByUserIdAndStrategyKeyAndDeletedFalse(userId, strategyKey)
                .or(() -> configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(strategyKey));
    }

    /**
     * Returns existing global config for the strategy key, or builds one with production-safe defaults.
     * Does NOT persist — caller decides whether to save.
     */
    public StrategyExecutionConfig getOrCreateDefault(String strategyKey) {
        return configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(strategyKey)
                .orElseGet(() -> {
                    StrategyExecutionConfig cfg = new StrategyExecutionConfig();
                    cfg.setStrategyKey(strategyKey);
                    return cfg;
                });
    }

    public List<StrategyExecutionConfigDto> listAll() {
        return configRepository.findAllByUserIdIsNullAndDeletedFalseOrderByStrategyKeyAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ── Trader self-service ───────────────────────────────────────────────────

    /** All trader-specific overrides for a given user (personal rows only). */
    public List<TraderExecutionConfigDto> listForUser(UUID userId) {
        return configRepository.findByUserIdAndDeletedFalseOrderByStrategyKeyAsc(userId)
                .stream()
                .map(c -> toTraderDto(c, false))
                .toList();
    }

    /**
     * Returns all globally-configured strategies with effective values for the user.
     * Personal overrides take precedence; global admin config is the fallback (isGlobalFallback=true).
     * This is what the trader settings page should use — shows all strategies, not just overrides.
     */
    public List<TraderExecutionConfigDto> listAllEffectiveForUser(UUID userId) {
        List<StrategyExecutionConfig> globals =
                configRepository.findAllByUserIdIsNullAndDeletedFalseOrderByStrategyKeyAsc();
        // Index user overrides by strategyKey for O(1) lookup
        java.util.Map<String, StrategyExecutionConfig> userOverrides =
                configRepository.findByUserIdAndDeletedFalseOrderByStrategyKeyAsc(userId)
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                StrategyExecutionConfig::getStrategyKey,
                                c -> c));
        return globals.stream()
                .map(global -> {
                    StrategyExecutionConfig override = userOverrides.get(global.getStrategyKey());
                    return override != null
                            ? toTraderDto(override, false)
                            : toTraderDto(global, true);
                })
                .toList();
    }

    /**
     * Returns effective config for a strategy from a trader's perspective.
     * If trader has a personal override, returns it (isGlobalFallback=false).
     * Otherwise returns global admin config with isGlobalFallback=true.
     * If neither exists, returns safe defaults with isGlobalFallback=true.
     */
    public TraderExecutionConfigDto getOrCreateForUserDto(UUID userId, String strategyKey) {
        Optional<StrategyExecutionConfig> userCfg =
                configRepository.findByUserIdAndStrategyKeyAndDeletedFalse(userId, strategyKey);
        if (userCfg.isPresent()) {
            return toTraderDto(userCfg.get(), false);
        }
        Optional<StrategyExecutionConfig> globalCfg =
                configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(strategyKey);
        if (globalCfg.isPresent()) {
            return toTraderDto(globalCfg.get(), true);
        }
        // Return safe defaults — no DB row yet
        StrategyExecutionConfig defaults = new StrategyExecutionConfig();
        defaults.setStrategyKey(strategyKey);
        defaults.setUserId(userId);
        return toTraderDto(defaults, true);
    }

    /**
     * Applies trader-editable fields only. Admin-controlled fields (liveEnabled, executionMode,
     * allocatedCapital, etc.) are never touched by this method.
     * Creates a user-specific row on first call; copies admin baseline for read-only fields.
     */
    @Transactional
    public TraderExecutionConfigDto patchForUser(UUID userId, String strategyKey,
                                                 TraderExecutionConfigPatchRequest req) {
        StrategyExecutionConfig cfg = configRepository
                .findByUserIdAndStrategyKeyAndDeletedFalse(userId, strategyKey)
                .orElseGet(() -> {
                    StrategyExecutionConfig newCfg = new StrategyExecutionConfig();
                    newCfg.setUserId(userId);
                    newCfg.setStrategyKey(strategyKey);
                    // Copy admin-controlled read-only fields from global baseline
                    configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(strategyKey)
                            .ifPresent(global -> {
                                newCfg.setExecutionMode(global.getExecutionMode());
                                newCfg.setLiveEnabled(global.isLiveEnabled());
                                newCfg.setPaperEnabled(global.isPaperEnabled());
                                newCfg.setAllocatedCapital(global.getAllocatedCapital());
                                newCfg.setMaxTradeQuantity(global.getMaxTradeQuantity());
                                newCfg.setAutoDisableOnLoss(global.isAutoDisableOnLoss());
                                newCfg.setLiveConfirmationRequired(global.isLiveConfirmationRequired());
                            });
                    return newCfg;
                });

        // Apply only trader-editable fields
        cfg.setEnabled(req.enabled());
        cfg.setTelegramEnabled(req.telegramEnabled());
        cfg.setForceFixedQty(req.forceFixedQty());
        cfg.setFixedQty(req.fixedQty());
        cfg.setMaxPositions(req.maxPositions());
        cfg.setDailyLossLimit(req.dailyLossLimit());
        cfg.setCooldownMinutes(req.cooldownMinutes());
        cfg.setAllowPyramiding(req.allowPyramiding());
        cfg.setEmergencyStopEnabled(req.emergencyStopEnabled());

        cfg = configRepository.save(cfg);
        log.info("trader_config.patched userId={} strategyKey={}", userId, strategyKey);
        return toTraderDto(cfg, false);
    }

    public TraderExecutionConfigDto toTraderDto(StrategyExecutionConfig cfg, boolean isGlobalFallback) {
        return new TraderExecutionConfigDto(
                cfg.getId(),
                cfg.getStrategyKey(),
                isGlobalFallback,
                cfg.getExecutionMode(),
                cfg.isLiveEnabled(),
                cfg.isPaperEnabled(),
                cfg.isEnabled(),
                cfg.isTelegramEnabled(),
                cfg.isForceFixedQty(),
                cfg.getFixedQty(),
                cfg.getMaxPositions(),
                cfg.getDailyLossLimit(),
                cfg.getCooldownMinutes(),
                cfg.isAllowPyramiding(),
                cfg.isEmergencyStopEnabled()
        );
    }

    public StrategyExecutionConfigDto getById(UUID id) {
        return toDto(findOrThrow(id));
    }

    @Transactional
    public StrategyExecutionConfigDto create(StrategyExecutionConfigRequest req) {
        configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(req.strategyKey()).ifPresent(existing -> {
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
