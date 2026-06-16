package com.stokr.admin.signal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record AdminSignalBenchmarkResultDto(
        String status,
        String strategyKey,
        LocalDate fromDate,
        LocalDate toDate,
        boolean purgedBeforeRerun,
        long purgedCount,
        int strategiesReplayed,
        int totalSignalsGenerated,
        int outcomesProcessed,
        List<AdminSignalStrategyStatsDto> strategyStats,
        Map<String, Object> replayDetails
) {}
