package com.stokr.strategy.service;

import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.dto.StrategyExecutionConfigDto;
import com.stokr.strategy.dto.StrategyExecutionConfigRequest;
import com.stokr.strategy.dto.TraderExecutionConfigDto;
import com.stokr.strategy.dto.TraderExecutionConfigPatchRequest;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategyExecutionConfigRepository;
import com.stokr.strategy.repository.StrategyRuntimeBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stokr.strategy.dto.AssetClassAllocationDto;
import com.stokr.strategy.dto.TraderCapitalAllocationView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyExecutionConfigService {

    private final StrategyExecutionConfigRepository configRepository;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final StrategyRuntimeBindingRepository runtimeBindingRepository;

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
     * Does NOT persist ??? caller decides whether to save.
     */
    public StrategyExecutionConfig getOrCreateDefault(String strategyKey) {
        return configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(strategyKey)
                .orElseGet(() -> {
                    StrategyExecutionConfig cfg = new StrategyExecutionConfig();
                    cfg.setStrategyKey(strategyKey);
                    return cfg;
                });
    }

    /**
     * Ensures a persisted global execution config exists for the strategy key.
     * Used when catalog strategies were registered without sizing config rows (blocks OMS execution).
     */
    @Transactional
    public StrategyExecutionConfig ensureGlobalExecutionConfig(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            throw new IllegalArgumentException("strategyKey is required");
        }
        String key = strategyKey.trim();
        return configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(key)
                .orElseGet(() -> {
                    StrategyExecutionConfig cfg = new StrategyExecutionConfig();
                    cfg.setStrategyKey(key);
                    applyCatalogDefaults(cfg, strategyDefinitionRepository.findByStrategyKeyAndDeletedFalse(key).orElse(null));
                    cfg = configRepository.save(cfg);
                    log.warn("execution_config.auto_provisioned strategyKey={} id={}", key, cfg.getId());
                    return cfg;
                });
    }

    private static void applyCatalogDefaults(StrategyExecutionConfig cfg, StrategyDefinition definition) {
        cfg.setEnabled(true);
        cfg.setSizingMode("FIXED_QUANTITY");
        cfg.setForceFixedQty(true);
        cfg.setFixedQty(BigDecimal.ONE);
        cfg.setMaxPositions(3);
        cfg.setCapitalUtilizationMode("FULLY_ALLOCATED");
        if (definition != null) {
            cfg.setStrategy(definition);
            String mode = definition.getExecutionMode();
            if (mode != null && !mode.isBlank() && !"ALL".equalsIgnoreCase(mode)) {
                cfg.setExecutionMode(mode);
            } else {
                cfg.setExecutionMode("BOTH");
            }
            cfg.setLiveEnabled(definition.isSupportsLive());
            cfg.setPaperEnabled(definition.isSupportsPaper());
        } else {
            cfg.setExecutionMode("BOTH");
            cfg.setLiveEnabled(true);
            cfg.setPaperEnabled(true);
        }
    }

    public List<StrategyExecutionConfigDto> listAll() {
        return configRepository.findAllByUserIdIsNullAndDeletedFalseOrderByStrategyKeyAsc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ?????? Trader self-service ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

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
     * This is what the trader settings page should use ??? shows all strategies, not just overrides.
     */
    public List<TraderExecutionConfigDto> listAllEffectiveForUser(UUID userId) {
        Map<String, StrategyDefinition> catalogByKey = loadCatalogDefinitionsByKey();
        Map<String, StrategyExecutionConfig> globals = configRepository
                .findAllByUserIdIsNullAndDeletedFalseOrderByStrategyKeyAsc()
                .stream()
                .collect(Collectors.toMap(StrategyExecutionConfig::getStrategyKey, c -> c, (a, b) -> a));
        Map<String, StrategyExecutionConfig> userOverrides = configRepository
                .findByUserIdAndDeletedFalseOrderByStrategyKeyAsc(userId)
                .stream()
                .collect(Collectors.toMap(StrategyExecutionConfig::getStrategyKey, c -> c, (a, b) -> a));

        // Union: every enabled catalog strategy + any admin global config row
        LinkedHashMap<String, StrategyDefinition> keys = new LinkedHashMap<>(catalogByKey);
        for (String key : globals.keySet()) {
            keys.putIfAbsent(key, catalogByKey.get(key));
        }

        return keys.keySet().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(strategyKey -> effectiveForUser(
                        userId,
                        strategyKey,
                        keys.get(strategyKey),
                        globals.get(strategyKey),
                        userOverrides.get(strategyKey)))
                .toList();
    }

    /** Catalog-visible strategies plus platform scanners bound at runtime (e.g. NSE_SPIKE_DETECTION). */
    private Map<String, StrategyDefinition> loadCatalogDefinitionsByKey() {
        LinkedHashMap<String, StrategyDefinition> merged = new LinkedHashMap<>();
        for (StrategyDefinition def : strategyDefinitionRepository
                .findAllByDeletedFalseAndEnabledTrueAndVisibleToUsersTrueOrderByStrategyKeyAsc()) {
            merged.put(def.getStrategyKey(), def);
        }
        for (StrategyRuntimeBinding binding : runtimeBindingRepository.findAllActiveBindings()) {
            StrategyDefinition def = binding.getStrategyCatalog();
            if (def != null && !def.isDeleted() && def.isEnabled()) {
                merged.putIfAbsent(def.getStrategyKey(), def);
            }
        }
        return merged;
    }

    private TraderExecutionConfigDto effectiveForUser(
            UUID userId,
            String strategyKey,
            StrategyDefinition definition,
            StrategyExecutionConfig global,
            StrategyExecutionConfig userOverride) {
        if (userOverride != null) {
            return toTraderDto(userOverride, false, definition);
        }
        if (global != null) {
            return toTraderDto(global, true, definition);
        }
        StrategyExecutionConfig defaults = new StrategyExecutionConfig();
        defaults.setStrategyKey(strategyKey);
        defaults.setUserId(userId);
        if (definition != null) {
            defaults.setStrategy(definition);
            String mode = definition.getExecutionMode();
            if (mode != null && !mode.isBlank() && !"ALL".equalsIgnoreCase(mode)) {
                defaults.setExecutionMode(mode);
            }
            defaults.setLiveEnabled(definition.isSupportsLive());
            defaults.setPaperEnabled(definition.isSupportsPaper());
        }
        return toTraderDto(defaults, true, definition);
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
        StrategyDefinition definition = strategyDefinitionRepository
                .findByStrategyKeyAndDeletedFalse(strategyKey)
                .orElse(null);
        if (userCfg.isPresent()) {
            return toTraderDto(userCfg.get(), false, definition);
        }
        Optional<StrategyExecutionConfig> globalCfg =
                configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(strategyKey);
        if (globalCfg.isPresent()) {
            return toTraderDto(globalCfg.get(), true, definition);
        }
        return effectiveForUser(userId, strategyKey, definition, null, null);
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
        StrategyDefinition definition = strategyDefinitionRepository
                .findByStrategyKeyAndDeletedFalse(strategyKey)
                .orElse(null);
        return toTraderDto(cfg, false, definition);
    }

    // ?????? Trader capital allocation (asset-class fan-out) ?????????????????????????????????????????????????????????????????????

    /** Display order of the four asset-class buckets shown in the trader UI. */
    private static final List<String> ASSET_CLASSES = List.of("CASH", "F&O", "CURRENCY", "MCX");

    /**
     * Known platform strategies whose catalog asset_class may not be populated.
     * These explicit mappings take precedence over catalog-derived classification.
     */
    private static final Map<String, Set<String>> EXPLICIT_MEMBERS = Map.of(
            "CASH", Set.of("ADV_CASH", "EARLY_BREAKOUT", "GAP_FILL", "INDEX_HUNT",
                    "NSE_SPIKE_DETECTION", "PRE_OPEN_GAP_OI", "S3_VWAP_RETEST",
                    "S7_RANGE_FADE", "SECTOR_LAGGARD", "VWAP_BOUNCE"),
            "CURRENCY", Set.of("EUR_INR_MEAN_REVERSION", "USD_INR_MOMENTUM"));

    /** Production-safe defaults shown when a trader has no personal capital overrides yet. */
    private static final Map<String, AssetClassAllocationDto> DEFAULTS = Map.of(
            "CASH", new AssetClassAllocationDto("CASH", BigDecimal.valueOf(50000), 10, BigDecimal.valueOf(5000), true, BigDecimal.valueOf(5000), false, List.of()),
            "F&O", new AssetClassAllocationDto("F&O", BigDecimal.valueOf(30000), 5, BigDecimal.valueOf(6000), true, BigDecimal.valueOf(3000), false, List.of()),
            "CURRENCY", new AssetClassAllocationDto("CURRENCY", BigDecimal.valueOf(15000), 3, BigDecimal.valueOf(5000), true, BigDecimal.valueOf(1500), false, List.of()),
            "MCX", new AssetClassAllocationDto("MCX", BigDecimal.valueOf(5000), 2, BigDecimal.valueOf(2500), false, BigDecimal.valueOf(500), false, List.of()));

    private static final BigDecimal DEFAULT_TOTAL_CAPITAL = BigDecimal.valueOf(100000);

    /**
     * Resolves which strategy keys belong to each asset-class bucket. Explicit platform
     * mappings win; everything else is classified from the catalog (asset_class / segment /
     * derivative flags). Runtime-bound scanners are included via {@link #loadCatalogDefinitionsByKey()}.
     */
    private Map<String, List<String>> resolveMembersByAssetClass() {
        Map<String, String> keyToClass = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        EXPLICIT_MEMBERS.forEach((cls, keys) -> keys.forEach(k -> keyToClass.put(k, cls)));
        for (StrategyDefinition def : loadCatalogDefinitionsByKey().values()) {
            keyToClass.putIfAbsent(def.getStrategyKey(), classifyAssetClass(def));
        }
        Map<String, List<String>> byClass = new LinkedHashMap<>();
        for (String cls : ASSET_CLASSES) {
            byClass.put(cls, new ArrayList<>());
        }
        keyToClass.forEach((key, cls) -> byClass.computeIfAbsent(cls, c -> new ArrayList<>()).add(key));
        byClass.values().forEach(list -> list.sort(String.CASE_INSENSITIVE_ORDER));
        return byClass;
    }

    private static String classifyAssetClass(StrategyDefinition def) {
        String seg = def.getSegment() == null ? "" : def.getSegment().trim().toUpperCase();
        String ac = def.getAssetClass() == null ? "" : def.getAssetClass().trim().toUpperCase();
        if ("MCX".equals(seg) || "COMMODITY".equals(ac)) return "MCX";
        if ("CDS".equals(seg) || "CURRENCY".equals(ac)) return "CURRENCY";
        if (def.isDerivativeEnabled() || def.isFuturesStrategyEnabled() || def.isOptionStrategyEnabled()
                || "FUTURES".equals(ac) || "OPTIONS".equals(ac) || "NFO".equals(seg)) {
            return "F&O";
        }
        return "CASH";
    }

    /**
     * Builds the trader-facing capital allocation view by aggregating the effective
     * per-strategy configs for each asset-class bucket. When a bucket has no personal
     * overrides, production-safe defaults are returned for that bucket.
     */
    public TraderCapitalAllocationView getCapitalAllocation(UUID userId) {
        Map<String, List<String>> membersByClass = resolveMembersByAssetClass();
        Map<String, StrategyExecutionConfig> userOverrides = configRepository
                .findByUserIdAndDeletedFalseOrderByStrategyKeyAsc(userId)
                .stream()
                .collect(Collectors.toMap(StrategyExecutionConfig::getStrategyKey, c -> c, (a, b) -> a));

        List<AssetClassAllocationDto> allocations = new ArrayList<>();
        for (String cls : ASSET_CLASSES) {
            List<String> members = membersByClass.getOrDefault(cls, List.of());
            allocations.add(aggregateBucket(cls, members, userOverrides));
        }
        BigDecimal total = allocations.stream()
                .map(AssetClassAllocationDto::allocatedCapital)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(DEFAULT_TOTAL_CAPITAL) < 0) {
            total = DEFAULT_TOTAL_CAPITAL;
        }
        return new TraderCapitalAllocationView(total, allocations);
    }

    private AssetClassAllocationDto aggregateBucket(
            String cls, List<String> members, Map<String, StrategyExecutionConfig> userOverrides) {
        // Representative = first member with a personal override (all members are written identically).
        StrategyExecutionConfig rep = members.stream()
                .map(userOverrides::get)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        AssetClassAllocationDto def = DEFAULTS.getOrDefault(cls,
                new AssetClassAllocationDto(cls, BigDecimal.ZERO, 1, BigDecimal.ZERO, false, BigDecimal.ZERO, false, List.of()));
        if (rep == null) {
            return new AssetClassAllocationDto(cls, def.allocatedCapital(), def.maxConcurrentPositions(),
                    def.perTradeLimitAmount(), def.enabled(), def.dailyLossLimit(), def.dailyLossLimitEnabled(), members);
        }
        BigDecimal allocated = rep.getAllocatedCapital() != null ? rep.getAllocatedCapital() : def.allocatedCapital();
        int maxPos = rep.getMaxPositions() > 0 ? rep.getMaxPositions() : def.maxConcurrentPositions();
        BigDecimal perTrade = rep.getMaxCapitalPerTrade() != null
                ? rep.getMaxCapitalPerTrade()
                : allocated.divide(BigDecimal.valueOf(Math.max(maxPos, 1)), 0, RoundingMode.FLOOR);
        boolean capitalSizingActive = "FIXED_CAPITAL_PER_TRADE".equals(rep.getSizingMode()) && !rep.isForceFixedQty();
        boolean lossEnabled = rep.getDailyLossLimit() != null;
        BigDecimal lossLimit = rep.getDailyLossLimit() != null ? rep.getDailyLossLimit() : def.dailyLossLimit();
        return new AssetClassAllocationDto(cls, allocated, maxPos, perTrade, capitalSizingActive,
                lossLimit, lossEnabled, members);
    }

    /**
     * Persists a trader's capital allocation by fanning each asset-class bucket out to its
     * member strategies' personal execution-config rows. Enabled buckets switch to
     * FIXED_CAPITAL_PER_TRADE sizing; disabled buckets revert to fixed-qty sizing without
     * touching the strategy's enabled flag (admin-controlled routing fields are never modified).
     */
    @Transactional
    public TraderCapitalAllocationView saveCapitalAllocation(UUID userId, TraderCapitalAllocationView req) {
        Map<String, List<String>> membersByClass = resolveMembersByAssetClass();
        for (AssetClassAllocationDto alloc : req.allocations()) {
            if (alloc == null || alloc.assetClass() == null) continue;
            List<String> members = membersByClass.getOrDefault(alloc.assetClass().trim().toUpperCase(), List.of());
            for (String strategyKey : members) {
                applyCapitalToStrategy(userId, strategyKey, alloc);
            }
        }
        log.info("trader_capital.saved userId={} buckets={}", userId, req.allocations().size());
        return getCapitalAllocation(userId);
    }

    private void applyCapitalToStrategy(UUID userId, String strategyKey, AssetClassAllocationDto alloc) {
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
                                newCfg.setEnabled(global.isEnabled());
                                newCfg.setMaxTradeQuantity(global.getMaxTradeQuantity());
                                newCfg.setLiveConfirmationRequired(global.isLiveConfirmationRequired());
                            });
                    return newCfg;
                });

        BigDecimal allocated = alloc.allocatedCapital() != null ? alloc.allocatedCapital() : BigDecimal.ZERO;
        int maxPos = Math.max(alloc.maxConcurrentPositions(), 1);
        BigDecimal perTrade = alloc.perTradeLimitAmount() != null && alloc.perTradeLimitAmount().signum() > 0
                ? alloc.perTradeLimitAmount()
                : allocated.divide(BigDecimal.valueOf(maxPos), 0, RoundingMode.FLOOR);

        cfg.setAllocatedCapital(allocated);
        cfg.setMaxPositions(maxPos);
        cfg.setMaxCapitalPerTrade(perTrade);

        if (alloc.enabled()) {
            // Capital sizing active: qty = floor(maxCapitalPerTrade / price)
            cfg.setSizingMode("FIXED_CAPITAL_PER_TRADE");
            cfg.setForceFixedQty(false);
        } else {
            // Disabled bucket ??? revert sizing only; leave strategy enabled flag untouched.
            cfg.setSizingMode("FIXED_QUANTITY");
            cfg.setForceFixedQty(true);
        }

        // Daily loss limit is enforced per-strategy by StrategyDailyLossLimitRule and
        // auto-resets each IST business day via StrategyDailyLossTrackerService.
        if (alloc.dailyLossLimitEnabled() && alloc.dailyLossLimit() != null
                && alloc.dailyLossLimit().signum() > 0) {
            cfg.setDailyLossLimit(alloc.dailyLossLimit());
            cfg.setAutoDisableOnLoss(true);
        } else {
            cfg.setDailyLossLimit(null);
            cfg.setAutoDisableOnLoss(false);
        }

        configRepository.save(cfg);
    }

    public TraderExecutionConfigDto toTraderDto(StrategyExecutionConfig cfg, boolean isGlobalFallback) {
        StrategyDefinition definition = cfg.getStrategy();
        if (definition == null) {
            definition = strategyDefinitionRepository
                    .findByStrategyKeyAndDeletedFalse(cfg.getStrategyKey())
                    .orElse(null);
        }
        return toTraderDto(cfg, isGlobalFallback, definition);
    }

    public TraderExecutionConfigDto toTraderDto(
            StrategyExecutionConfig cfg, boolean isGlobalFallback, StrategyDefinition definition) {
        String displayName = definition != null && definition.getDisplayName() != null && !definition.getDisplayName().isBlank()
                ? definition.getDisplayName()
                : humanizeKey(cfg.getStrategyKey());
        String riskLevel = definition != null ? definition.getRiskLevel() : "MEDIUM";
        UUID definitionId = definition != null ? definition.getId() : null;
        return new TraderExecutionConfigDto(
                cfg.getId(),
                cfg.getStrategyKey(),
                isGlobalFallback,
                definitionId,
                displayName,
                riskLevel,
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

    private static String humanizeKey(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return "Strategy";
        }
        return strategyKey.replace('_', ' ');
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

    // ?????? Helpers ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

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
