package com.stokr.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StrategyMappingService {

    private final StrategyUniverseMappingRepository mappingRepository;
    private final UniverseGroupRepository groupRepository;

    public List<StrategyUniverseMapping> listAll() {
        return mappingRepository.findAll();
    }

    public List<StrategyUniverseMapping> listByStrategy(Long strategyId) {
        return mappingRepository.findByStrategyId(strategyId);
    }

    public List<StrategyUniverseMapping> listActive() {
        return mappingRepository.findByRuntimeEnabledTrue();
    }

    @Transactional
    public StrategyUniverseMapping create(Long strategyId, Long universeGroupId,
                                           Integer maxPositions, Integer scanIntervalSeconds,
                                           String riskProfile) {
        if (mappingRepository.existsByStrategyIdAndUniverseGroupId(strategyId, universeGroupId)) {
            throw new IllegalArgumentException("Mapping already exists for this strategy and group");
        }
        StrategyUniverseMapping m = StrategyUniverseMapping.builder()
                .strategyId(strategyId)
                .universeGroupId(universeGroupId)
                .runtimeEnabled(true)
                .maxPositions(maxPositions != null ? maxPositions : 2)
                .scanIntervalSeconds(scanIntervalSeconds != null ? scanIntervalSeconds : 60)
                .riskProfile(riskProfile != null ? riskProfile : "MEDIUM")
                .build();
        return mappingRepository.save(m);
    }

    @Transactional
    public StrategyUniverseMapping update(Long id, Boolean runtimeEnabled,
                                           Integer maxPositions, Integer scanIntervalSeconds,
                                           String riskProfile) {
        StrategyUniverseMapping m = mappingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + id));
        if (runtimeEnabled != null) m.setRuntimeEnabled(runtimeEnabled);
        if (maxPositions != null) m.setMaxPositions(maxPositions);
        if (scanIntervalSeconds != null) m.setScanIntervalSeconds(scanIntervalSeconds);
        if (riskProfile != null) m.setRiskProfile(riskProfile);
        return mappingRepository.save(m);
    }

    @Transactional
    public void delete(Long id) {
        mappingRepository.deleteById(id);
    }

    public Optional<UniverseGroup> resolveGroupForStrategy(Long strategyId) {
        return mappingRepository.findByStrategyId(strategyId).stream()
                .filter(StrategyUniverseMapping::isRuntimeEnabled)
                .findFirst()
                .flatMap(m -> groupRepository.findById(m.getUniverseGroupId()));
    }
}
