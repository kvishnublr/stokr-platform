package com.stokr.strategy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.dto.metadata.StrategyMetadataResponseDto;
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
            throw new BadRequestException("No parameter metadata published for strategy: " + key);
        }
        try {
            StrategyMetadataResponseDto dto = objectMapper.readValue(raw, StrategyMetadataResponseDto.class);
            try {
                StrategyMetadataDocumentValidator.validateOrThrow(dto);
            } catch (IllegalStateException ise) {
                throw new BadRequestException(ise.getMessage());
            }
            return dto;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("strategy.metadata.parse_failed strategyKey={}", key, e);
            throw new BadRequestException("Invalid strategy metadata document for " + key);
        }
    }
}
