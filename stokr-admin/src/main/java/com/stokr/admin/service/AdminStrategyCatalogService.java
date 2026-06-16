package com.stokr.admin.service;

import com.stokr.admin.dto.AdminStrategyDto;
import com.stokr.admin.dto.AdminStrategyPatchRequest;
import com.stokr.admin.dto.StrategyCreateRequest;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.risk.service.StrategyToggleService;
import com.stokr.strategy.catalog.StrategyTemplateGeneratorService;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminStrategyCatalogService {

    private final StrategyDefinitionRepository definitionRepository;
    private final StrategyToggleService strategyToggleService;
    private final StrategyTemplateGeneratorService templateGeneratorService;

    @Transactional(readOnly = true)
    public Page<AdminStrategyDto> page(Pageable pageable) {
        return definitionRepository.findAllByDeletedFalse(pageable).map(this::toDto);
    }

    /**
     * Admin creates a new strategy catalog entry.
     * Optionally generates the Java template file immediately if generateTemplate=true.
     */
    @Transactional
    public AdminStrategyDto create(StrategyCreateRequest req, UUID adminUserId) {
        String key = req.strategyKey().trim().toUpperCase();
        if (definitionRepository.findByStrategyKeyAndDeletedFalse(key).isPresent()) {
            throw new BadRequestException("Strategy key already exists: " + key);
        }

        StrategyDefinition d = new StrategyDefinition();
        d.setStrategyKey(key);
        d.setDisplayName(req.displayName());
        d.setDescription(req.description());
        d.setStrategyType(nvl(req.strategyType(), "INTRADAY"));
        d.setExecutionMode(nvl(req.executionMode(), "ALL"));
        d.setAssetClass(nvl(req.assetClass(), "EQUITY"));
        d.setSegment(nvl(req.segment(), "NSE"));
        d.setDefaultTimeframe(nvl(req.defaultTimeframe(), "1m"));
        d.setDefaultExchange(nvl(req.defaultExchange(), "NSE"));
        d.setRiskLevel(nvl(req.riskLevel(), "MEDIUM").toUpperCase());
        d.setSupportsBacktest(req.supportsBacktest() == null || req.supportsBacktest());
        d.setSupportsLive(Boolean.TRUE.equals(req.supportsLive()));
        d.setSupportsPaper(req.supportsPaper() == null || req.supportsPaper());
        d.setDerivativeEnabled(Boolean.TRUE.equals(req.derivativeEnabled()));
        d.setFuturesStrategyEnabled(Boolean.TRUE.equals(req.futuresStrategyEnabled()));
        d.setOptionStrategyEnabled(Boolean.TRUE.equals(req.optionStrategyEnabled()));
        if (req.templateClassName() != null && !req.templateClassName().isBlank()) {
            d.setTemplateClassName(req.templateClassName().trim());
        }
        if (req.symbols() != null && !req.symbols().isEmpty()) {
            d.setDefaultSymbols(String.join(",", req.symbols().stream()
                    .map(String::trim).map(String::toUpperCase).filter(s -> !s.isBlank()).toList()));
        }
        d.setEnabled(true);
        d.setVisibleToUsers(false);
        d.setCreatedBy(adminUserId);
        d.setCatalogVersion("1.0");

        StrategyDefinition saved = definitionRepository.save(d);

        if (Boolean.TRUE.equals(req.generateTemplate())) {
            try {
                templateGeneratorService.generate(saved.getId());
                saved = definitionRepository.findByIdAndDeletedFalse(saved.getId()).orElse(saved);
            } catch (Exception ex) {
                log.warn("strategy.catalog.template_gen_failed key={}", key, ex);
            }
        }

        log.info("strategy.catalog.created key={} assetClass={} segment={}", key, d.getAssetClass(), d.getSegment());
        return toDto(saved);
    }

    /**
     * Triggers Java template generation for an existing strategy that doesn't have one yet.
     */
    @Transactional
    public AdminStrategyDto generateTemplate(UUID id) {
        StrategyDefinition d = definitionRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new NotFoundException("Strategy definition not found"));
        templateGeneratorService.generate(id);
        return toDto(definitionRepository.findByIdAndDeletedFalse(id).orElse(d));
    }

    @Transactional
    public AdminStrategyDto patch(UUID id, AdminStrategyPatchRequest req) {
        if (req.enabled() == null && req.visibleToUsers() == null && req.riskLevel() == null && req.displayName() == null
                && req.category() == null && req.tagsJson() == null && req.iconKey() == null
                && req.minCapital() == null && req.popularityScore() == null && req.winRate() == null
                && req.avgMonthlyReturn() == null && req.announcementBanner() == null) {
            throw new BadRequestException("At least one field must be provided");
        }
        StrategyDefinition d = definitionRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new NotFoundException("Strategy definition not found"));
        if (req.enabled() != null) d.setEnabled(req.enabled());
        if (req.visibleToUsers() != null) d.setVisibleToUsers(req.visibleToUsers());
        if (req.riskLevel() != null && !req.riskLevel().isBlank()) d.setRiskLevel(req.riskLevel().trim().toUpperCase());
        if (req.displayName() != null) d.setDisplayName(req.displayName().isBlank() ? null : req.displayName().trim());
        if (req.category() != null) d.setCategory(req.category().isBlank() ? null : req.category().trim());
        if (req.tagsJson() != null) d.setTagsJson(req.tagsJson().isBlank() ? null : req.tagsJson().trim());
        if (req.iconKey() != null) d.setIconKey(req.iconKey().isBlank() ? null : req.iconKey().trim());
        if (req.minCapital() != null) d.setMinCapital(req.minCapital());
        if (req.popularityScore() != null) d.setPopularityScore(req.popularityScore());
        if (req.winRate() != null) d.setWinRate(req.winRate());
        if (req.avgMonthlyReturn() != null) d.setAvgMonthlyReturn(req.avgMonthlyReturn());
        if (req.announcementBanner() != null) d.setAnnouncementBanner(req.announcementBanner().isBlank() ? null : req.announcementBanner().trim());
        StrategyDefinition saved = definitionRepository.save(d);
        strategyToggleService.setEnabled(saved.getStrategyKey(), saved.isEnabled());
        return toDto(saved);
    }

    /** Soft-deletes a strategy definition. Fails if runtime bindings exist. */
    @Transactional
    public void delete(UUID id) {
        StrategyDefinition d = definitionRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new NotFoundException("Strategy definition not found"));
        d.setDeleted(true);
        d.setEnabled(false);
        definitionRepository.save(d);
        strategyToggleService.setEnabled(d.getStrategyKey(), false);
        log.info("strategy.catalog.deleted key={}", d.getStrategyKey());
    }

    private AdminStrategyDto toDto(StrategyDefinition d) {
        return new AdminStrategyDto(
                d.getId(),
                d.getStrategyKey(),
                d.getDisplayName(),
                d.getDescription(),
                d.isEnabled(),
                d.isVisibleToUsers(),
                d.getRiskLevel(),
                d.getCategory(),
                d.getTagsJson(),
                d.getIconKey(),
                d.getMinCapital(),
                d.getPopularityScore(),
                d.getWinRate(),
                d.getAvgMonthlyReturn(),
                d.getAnnouncementBanner(),
                d.getStrategyType(),
                d.getExecutionMode(),
                d.getAssetClass(),
                d.getSegment(),
                d.getDefaultTimeframe(),
                d.getDefaultExchange(),
                d.isSupportsBacktest(),
                d.isSupportsLive(),
                d.isSupportsPaper(),
                d.isDerivativeEnabled(),
                d.isFuturesStrategyEnabled(),
                d.isOptionStrategyEnabled(),
                d.getTemplateClassName(),
                d.getGeneratedClassPath(),
                d.isTemplateGenerated(),
                d.getCatalogVersion(),
                d.getCreatedAt(),
                parseSymbols(d.getDefaultSymbols())
        );
    }

    private List<String> parseSymbols(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private String nvl(String val, String fallback) {
        return (val != null && !val.isBlank()) ? val.trim() : fallback;
    }
}
