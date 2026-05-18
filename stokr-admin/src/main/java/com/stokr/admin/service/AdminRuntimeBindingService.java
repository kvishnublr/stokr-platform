package com.stokr.admin.service;

import com.stokr.admin.dto.RuntimeBindingCreateRequest;
import com.stokr.admin.dto.RuntimeBindingDto;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategyUniverseGroup;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategyRuntimeBindingRepository;
import com.stokr.strategy.repository.StrategyUniverseGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminRuntimeBindingService {

    private final StrategyRuntimeBindingRepository bindingRepository;
    private final StrategyDefinitionRepository definitionRepository;
    private final StrategyUniverseGroupRepository groupRepository;
    private final StrategyUniverseResolverService resolverService;

    @Transactional(readOnly = true)
    public Page<RuntimeBindingDto> page(Pageable pageable) {
        return bindingRepository.findAll(pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<RuntimeBindingDto> listForStrategy(UUID strategyCatalogId) {
        return bindingRepository.findAllByStrategyCatalogId(strategyCatalogId)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public RuntimeBindingDto create(RuntimeBindingCreateRequest req) {
        if (bindingRepository.existsByStrategyCatalogIdAndUniverseGroupId(
                req.strategyCatalogId(), req.universeGroupId())) {
            throw new BadRequestException("Binding already exists for this strategy + universe combination");
        }

        StrategyDefinition def = definitionRepository.findByIdAndDeletedFalse(req.strategyCatalogId())
                .orElseThrow(() -> new NotFoundException("Strategy not found: " + req.strategyCatalogId()));
        StrategyUniverseGroup group = groupRepository.findById(req.universeGroupId())
                .orElseThrow(() -> new NotFoundException("Universe group not found: " + req.universeGroupId()));

        StrategyRuntimeBinding b = new StrategyRuntimeBinding();
        b.setStrategyCatalog(def);
        b.setUniverseGroup(group);
        b.setRuntimeEnabled(Boolean.TRUE.equals(req.runtimeEnabled()));
        b.setMaxPositions(req.maxPositions() != null ? req.maxPositions() : 5);
        b.setCapitalLimit(req.capitalLimit());
        b.setRiskProfile(nvl(req.riskProfile(), "MEDIUM").toUpperCase());
        b.setScanIntervalSeconds(req.scanIntervalSeconds() != null ? req.scanIntervalSeconds() : 60);

        StrategyRuntimeBinding saved = bindingRepository.save(b);
        log.info("runtime.binding.created strategyKey={} groupKey={} runtimeEnabled={}",
                def.getStrategyKey(), group.getGroupKey(), saved.isRuntimeEnabled());
        return toDto(saved);
    }

    @Transactional
    public RuntimeBindingDto toggleRuntime(UUID bindingId, boolean enabled) {
        StrategyRuntimeBinding b = bindingRepository.findById(bindingId)
                .orElseThrow(() -> new NotFoundException("Runtime binding not found: " + bindingId));
        b.setRuntimeEnabled(enabled);
        StrategyRuntimeBinding saved = bindingRepository.save(b);
        log.info("runtime.binding.toggled id={} enabled={}", bindingId, enabled);
        return toDto(saved);
    }

    @Transactional
    public RuntimeBindingDto update(UUID bindingId, RuntimeBindingCreateRequest req) {
        StrategyRuntimeBinding b = bindingRepository.findById(bindingId)
                .orElseThrow(() -> new NotFoundException("Runtime binding not found: " + bindingId));
        if (req.runtimeEnabled() != null) b.setRuntimeEnabled(req.runtimeEnabled());
        if (req.maxPositions() != null) b.setMaxPositions(req.maxPositions());
        if (req.capitalLimit() != null) b.setCapitalLimit(req.capitalLimit());
        if (req.riskProfile() != null && !req.riskProfile().isBlank()) b.setRiskProfile(req.riskProfile().toUpperCase());
        if (req.scanIntervalSeconds() != null) b.setScanIntervalSeconds(req.scanIntervalSeconds());
        return toDto(bindingRepository.save(b));
    }

    @Transactional
    public void delete(UUID bindingId) {
        if (!bindingRepository.existsById(bindingId)) {
            throw new NotFoundException("Runtime binding not found: " + bindingId);
        }
        bindingRepository.deleteById(bindingId);
        log.info("runtime.binding.deleted id={}", bindingId);
    }

    private RuntimeBindingDto toDto(StrategyRuntimeBinding b) {
        StrategyDefinition def = b.getStrategyCatalog();
        StrategyUniverseGroup grp = b.getUniverseGroup();
        long symbolCount = resolverService.countSymbols(grp.getId());
        return new RuntimeBindingDto(
                b.getId(),
                def.getId(), def.getStrategyKey(), def.getDisplayName(),
                def.getAssetClass(), def.getSegment(),
                grp.getId(), grp.getGroupKey(), grp.getDisplayName(), grp.getInstrumentType(),
                b.isRuntimeEnabled(), b.getMaxPositions(), b.getCapitalLimit(),
                b.getRiskProfile(), b.getScanIntervalSeconds(),
                symbolCount, b.getCreatedAt(), b.getUpdatedAt()
        );
    }

    private String nvl(String val, String fallback) {
        return (val != null && !val.isBlank()) ? val.trim() : fallback;
    }
}
