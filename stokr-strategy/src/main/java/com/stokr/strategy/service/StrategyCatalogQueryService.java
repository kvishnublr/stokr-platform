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
import java.util.List;
import java.util.Map;
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
        Map<UUID, StrategyInstance> byDef = userId == null
                ? Map.of()
                : loadInstances(userId);
        List<String> keys = page.getContent().stream().map(StrategyDefinition::getStrategyKey).toList();
        Map<String, List<String>> universeGroupsByKey = loadUniverseGroups(keys);
        return page.map(def -> toDto(def, byDef.get(def.getId()), universeGroupsByKey.getOrDefault(def.getStrategyKey(), List.of())));
    }

    private Map<String, List<String>> loadUniverseGroups(List<String> strategyKeys) {
        if (strategyKeys.isEmpty()) return Map.of();
        Map<String, List<String>> result = new HashMap<>();
        for (StrategyRuntimeBinding b : runtimeBindingRepository.findAllActiveBindingsByStrategyKeys(strategyKeys)) {
            String key = b.getStrategyCatalog().getStrategyKey();
            String groupName = b.getUniverseGroup().getName();
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
