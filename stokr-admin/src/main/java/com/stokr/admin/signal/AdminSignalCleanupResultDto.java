package com.stokr.admin.signal;

import java.time.Instant;
import java.time.LocalDate;

public record AdminSignalCleanupResultDto(
        boolean dryRun,
        LocalDate fromDate,
        LocalDate toDate,
        String strategyKey,
        boolean includeReplayAndLab,
        long matchedCount,
        long deletedCount,
        Instant fromInstant,
        Instant toExclusiveInstant
) {}
