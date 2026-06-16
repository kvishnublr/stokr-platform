package com.stokr.strategy.service;

import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.dto.StrategyCatalogItemDto;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.repository.StrategyRuntimeBindingRepository;
import com.stokr.strategy.spec.StrategyDefinitionSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StrategyCatalogQueryService {

    private final StrategyDefinitionRepository definitionRepository;
    private final StrategyInstanceRepository instanceRepository;
    private final StrategyRuntimeBindingRepository runtimeBindingRepository;

    @Transactional(readOnly = true)
    public Page<StrategyCatalogItemDto> catalogForCatalog(Pageable pageable, UUID userId, String category, String riskLevel) {
        Specification<StrategyDefinition> spec = Specification
                .where(StrategyDefinitionSpecifications.catalogBase())
                .and(StrategyDefinitionSpecifications.categoryIgnoreCase(category))
                .and(StrategyDefinitionSpecifications.riskLevelIgnoreCase(riskLevel));
        Page<StrategyDefinition> page = definitionRepository.findAll(spec, pageable);
        List<StrategyDefinition> definitions = mergePlatformScannerDefinitions(page.getContent());
        Map<UUID, StrategyInstance> byDef = userId == null
                ? Map.of()
                : loadInstances(userId);
        List<String> keys = definitions.stream().map(StrategyDefinition::getStrategyKey).toList();
        Map<String, List<String>> universeGroupsByKey = loadUniverseGroups(keys);
        List<StrategyCatalogItemDto> items = definitions.stream()
                .map(def -> toDto(def, byDef.get(def.getId()), universeGroupsByKey.getOrDefault(def.getStrategyKey(), List.of())))
                .toList();
        return new org.springframework.data.domain.PageImpl<>(items, pageable, page.getTotalElements() + Math.max(0, items.size() - page.getNumberOfElements()));
    }

    /**
     * Include catalog-driven platform scanners even when {@code visible_to_users} was toggled off in admin.
     */
    private List<StrategyDefinition> mergePlatformScannerDefinitions(List<StrategyDefinition> catalogPage) {
        Map<UUID, StrategyDefinition> merged = new LinkedHashMap<>();
        for (StrategyDefinition def : catalogPage) {
            merged.put(def.getId(), def);
        }
        for (StrategyRuntimeBinding binding : runtimeBindingRepository.findAllActiveBindings()) {
            StrategyDefinition def = binding.getStrategyCatalog();
            if (def != null && !def.isDeleted()) {
                merged.putIfAbsent(def.getId(), def);
            }
        }
        return List.copyOf(merged.values());
    }

    private Map<String, List<String>> loadUniverseGroups(List<String> strategyKeys) {
        if (strategyKeys.isEmpty()) return Map.of();
        Map<String, List<String>> result = new HashMap<>();
        for (StrategyRuntimeBinding b : runtimeBindingRepository.findAllActiveBindingsByStrategyKeys(strategyKeys)) {
            String key = b.getStrategyCatalog().getStrategyKey();
            String groupName = b.getUniverseGroup().getDisplayName();
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(groupName);
        }
        return result;
    }

    private Map<UUID, StrategyInstance> loadInstances(UUID userId) {
        Map<UUID, StrategyInstance> map = new HashMap<>();
        for (StrategyInstance si : instanceRepository.findAllForUserWithDefinition(userId)) {
            map.put(si.getDefinition().getId(), si);
        }
        return map;
    }

    private static StrategyCatalogItemDto toDto(StrategyDefinition def, StrategyInstance instance, List<String> universeGroups) {
        String name = def.getDisplayName() != null && !def.getDisplayName().isBlank()
                ? def.getDisplayName()
                : humanize(def.getStrategyKey());
        boolean subscribed = instance != null;
        boolean subscriptionEnabled = instance != null && instance.isEnabled();
        return new StrategyCatalogItemDto(
                def.getId(),
                def.getStrategyKey(),
                name,
                def.getDescription(),
                def.getRiskLevel(),
                subscribed,
                subscriptionEnabled,
                def.getCategory(),
                def.getAssetClass() != null && !def.getAssetClass().isBlank() ? def.getAssetClass() : "EQUITY",
                def.getSegment() != null && !def.getSegment().isBlank() ? def.getSegment() : "NSE",
                def.getTagsJson(),
                def.getIconKey(),
                def.getMinCapital(),
                def.getPopularityScore(),
                def.getWinRate(),
                def.getAvgMonthlyReturn(),
                def.getAnnouncementBanner(),
                instance != null ? instance.getExecutionMode() : null,
                instance != null ? instance.getRuntimeState() : null,
                instance != null ? instance.getAllocationAmount() : null,
                instance != null ? instance.getRiskMultiplier() : null,
                universeGroups
        );
    }

    private static String humanize(String key) {
        if (key == null) {
            return "";
        }
        return key.replace('_', ' ').toLowerCase();
    }
}
