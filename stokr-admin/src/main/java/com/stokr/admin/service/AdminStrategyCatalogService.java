package com.stokr.admin.service;

import com.stokr.admin.dto.AdminStrategyDto;
import com.stokr.admin.dto.AdminStrategyPatchRequest;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.risk.service.StrategyToggleService;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminStrategyCatalogService {

    private final StrategyDefinitionRepository definitionRepository;
    private final StrategyToggleService strategyToggleService;

    @Transactional(readOnly = true)
    public Page<AdminStrategyDto> page(Pageable pageable) {
        return definitionRepository.findAllByDeletedFalse(pageable).map(this::toDto);
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
        if (req.enabled() != null) {
            d.setEnabled(req.enabled());
        }
        if (req.visibleToUsers() != null) {
            d.setVisibleToUsers(req.visibleToUsers());
        }
        if (req.riskLevel() != null && !req.riskLevel().isBlank()) {
            d.setRiskLevel(req.riskLevel().trim().toUpperCase());
        }
        if (req.displayName() != null) {
            d.setDisplayName(req.displayName().isBlank() ? null : req.displayName().trim());
        }
        if (req.category() != null) {
            d.setCategory(req.category().isBlank() ? null : req.category().trim());
        }
        if (req.tagsJson() != null) {
            d.setTagsJson(req.tagsJson().isBlank() ? null : req.tagsJson().trim());
        }
        if (req.iconKey() != null) {
            d.setIconKey(req.iconKey().isBlank() ? null : req.iconKey().trim());
        }
        if (req.minCapital() != null) {
            d.setMinCapital(req.minCapital());
        }
        if (req.popularityScore() != null) {
            d.setPopularityScore(req.popularityScore());
        }
        if (req.winRate() != null) {
            d.setWinRate(req.winRate());
        }
        if (req.avgMonthlyReturn() != null) {
            d.setAvgMonthlyReturn(req.avgMonthlyReturn());
        }
        if (req.announcementBanner() != null) {
            d.setAnnouncementBanner(req.announcementBanner().isBlank() ? null : req.announcementBanner().trim());
        }
        StrategyDefinition saved = definitionRepository.save(d);
        strategyToggleService.setEnabled(saved.getStrategyKey(), saved.isEnabled());
        return toDto(saved);
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
                d.getCreatedAt()
        );
    }
}
