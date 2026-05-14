package com.stokr.strategy.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.dto.metadata.StrategyMetadataResponseDto;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Fail-fast validation for all published strategy metadata JSON at startup.
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class StrategyMetadataStartupRunner implements CommandLineRunner {

    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) {
        for (StrategyDefinition def : strategyDefinitionRepository.findAllByDeletedFalse(Pageable.unpaged()).getContent()) {
            String raw = def.getParameterMetadataJson();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                StrategyMetadataResponseDto dto = objectMapper.readValue(raw, StrategyMetadataResponseDto.class);
                StrategyMetadataDocumentValidator.validateOrThrow(dto);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Invalid strategy metadata JSON for " + def.getStrategyKey(), e);
            }
        }
        log.info("strategy.metadata.startup_ok validatedDefinitionsWithMetadata");
    }
}
