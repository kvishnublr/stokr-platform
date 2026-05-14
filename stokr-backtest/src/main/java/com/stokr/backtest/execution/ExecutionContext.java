package com.stokr.backtest.execution;

import com.stokr.backtest.domain.BacktestRun;
import com.stokr.backtest.web.dto.ExecutionRequestDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable snapshot of a single backtest / replay execution (PR-3).
 */
public record ExecutionContext(
        UUID runId,
        UUID userId,
        String strategyKey,
        String symbol,
        String timeframe,
        String executionMode,
        String executionProfile,
        String feeModel,
        String slippageModel,
        long deterministicSeed,
        String timezone,
        String correlationId,
        int metadataSchemaVersion,
        long strategyDefinitionVersion,
        BigDecimal capital,
        Instant rangeStart,
        Instant rangeEnd,
        Map<String, Object> strategyParameters
) {
    public static ExecutionContext from(
            BacktestRun run,
            ExecutionRequestDto envelope,
            int metadataSchemaVersion,
            long strategyDefinitionVersion,
            String correlationId
    ) {
        String tz = envelope.range().timezone();
        if (tz == null || tz.isBlank()) {
            tz = "Asia/Kolkata";
        }
        Map<String, Object> sp = envelope.strategyParameters() == null
                ? Map.of()
                : Collections.unmodifiableMap(Map.copyOf(envelope.strategyParameters()));
        long seed = envelope.seed() != null ? envelope.seed() : run.getSeed();
        return new ExecutionContext(
                run.getId(),
                run.getUserId(),
                envelope.strategyKey(),
                envelope.symbol(),
                envelope.timeframe(),
                envelope.executionMode(),
                envelope.executionProfile(),
                envelope.feeModel(),
                envelope.slippageModel(),
                seed,
                tz,
                correlationId != null && !correlationId.isBlank() ? correlationId : UUID.randomUUID().toString(),
                metadataSchemaVersion,
                strategyDefinitionVersion,
                envelope.capital(),
                envelope.range().from(),
                envelope.range().to(),
                sp
        );
    }
}
