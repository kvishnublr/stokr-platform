package com.stokr.strategy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.dto.metadata.StrategyDeploymentDefaultsDto;
import com.stokr.strategy.dto.metadata.StrategyMetadataResponseDto;
import com.stokr.strategy.dto.metadata.StrategyPreviewMetricsDto;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.metadata.StrategyMetadataDocumentValidator;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyMetadataQueryService {

    private final StrategyDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public StrategyMetadataResponseDto byStrategyKey(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            throw new BadRequestException("strategyKey is required");
        }
        String key = strategyKey.trim();
        StrategyDefinition def = definitionRepository
                .findByStrategyKeyAndDeletedFalse(key)
                .orElseThrow(() -> new NotFoundException("Strategy definition not found: " + key));
        String raw = def.getParameterMetadataJson();
        if (raw == null || raw.isBlank()) {
            // Operational fallback: legacy/variant keys may not have dedicated metadata docs yet.
            // Reuse the canonical mean-reversion metadata so launcher is still usable.
            if (isIntradayFallbackKey(key)) {
                raw = definitionRepository.findByStrategyKeyAndDeletedFalse(StrategyKeys.MEAN_REVERSION_RANGE_FADE)
                        .map(StrategyDefinition::getParameterMetadataJson)
                        .orElse(null);
            }
        }
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("No parameter metadata published for strategy: " + key);
        }
        try {
            StrategyMetadataResponseDto dto = objectMapper.readValue(raw, StrategyMetadataResponseDto.class);
            dto = normalizeMetadataKey(dto, def);
            StrategyMetadataResponseDto effective = enrichPresentation(dto);
            try {
                StrategyMetadataDocumentValidator.validateOrThrow(effective);
            } catch (IllegalStateException ise) {
                throw new BadRequestException(ise.getMessage());
            }
            return effective;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("strategy.metadata.parse_failed strategyKey={}", key, e);
            throw new BadRequestException("Invalid strategy metadata document for " + key);
        }
    }

    private static boolean isIntradayFallbackKey(String key) {
        return StrategyKeys.MEAN_REVERSION_V2.equalsIgnoreCase(key)
                || StrategyKeys.OPENING_RANGE_BREAKOUT.equalsIgnoreCase(key)
                || StrategyKeys.VWAP_MEAN_REVERSION.equalsIgnoreCase(key)
                || StrategyKeys.MOMENTUM_BREAKOUT.equalsIgnoreCase(key)
                || StrategyKeys.EMA_TREND_FOLLOW.equalsIgnoreCase(key);
    }

    private StrategyMetadataResponseDto normalizeMetadataKey(StrategyMetadataResponseDto dto, StrategyDefinition def) {
        String expectedKey = def.getStrategyKey();
        String display = def.getDisplayName() == null || def.getDisplayName().isBlank() ? dto.displayName() : def.getDisplayName();
        if (expectedKey == null || expectedKey.isBlank()) {
            return dto;
        }
        if (expectedKey.equalsIgnoreCase(dto.strategyKey()) && display.equals(dto.displayName())) {
            return dto;
        }
        return new StrategyMetadataResponseDto(
                dto.schemaVersion(),
                expectedKey,
                display,
                dto.description(),
                dto.category(),
                dto.supportedMarkets(),
                dto.requiredIndicators(),
                dto.executionCapabilities(),
                dto.parameters(),
                dto.allowedTimeframes(),
                dto.allowedExecutionModes(),
                dto.allowedFeeModels(),
                dto.allowedSlippageModels(),
                dto.allowedExecutionProfiles(),
                dto.deploymentDefaults(),
                dto.previewMetrics()
        );
    }

    /**
     * Fills {@code deploymentDefaults} and {@code previewMetrics} when omitted from stored JSON so the API can drive
     * a trader-first launcher without duplicating catalog constants in the UI.
     */
    private StrategyMetadataResponseDto enrichPresentation(StrategyMetadataResponseDto dto) {
        if (!StrategyKeys.MEAN_REVERSION_RANGE_FADE.equalsIgnoreCase(dto.strategyKey())) {
            return dto;
        }
        StrategyDeploymentDefaultsDto dd = dto.deploymentDefaults();
        if (dd == null) {
            dd = new StrategyDeploymentDefaultsDto("NIFTY_FUT", "5m", "SIMULATED_DEFAULT", "PERCENT_2_BPS", "SPREAD_PROXY");
        }
        StrategyPreviewMetricsDto pm = dto.previewMetrics();
        if (pm == null) {
            pm = new StrategyPreviewMetricsDto(1.6d, 63d, 7.2d, "Moderate", 4d, "Intraday (session)");
        }
        if (dd == dto.deploymentDefaults() && pm == dto.previewMetrics()) {
            return dto;
        }
        return new StrategyMetadataResponseDto(
                dto.schemaVersion(),
                dto.strategyKey(),
                dto.displayName(),
                dto.description(),
                dto.category(),
                dto.supportedMarkets(),
                dto.requiredIndicators(),
                dto.executionCapabilities(),
                dto.parameters(),
                dto.allowedTimeframes(),
                dto.allowedExecutionModes(),
                dto.allowedFeeModels(),
                dto.allowedSlippageModels(),
                dto.allowedExecutionProfiles(),
                dd,
                pm
        );
    }
}
