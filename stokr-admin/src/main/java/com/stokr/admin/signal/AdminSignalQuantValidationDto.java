package com.stokr.admin.signal;

public record AdminSignalQuantValidationDto(
        long productionSampleSize,
        long replayTaggedCount,
        long labTaggedCount,
        boolean replayIsolatedFromProductionStats,
        boolean productionSampleAdequate,
        boolean expectancyStatsUsable,
        String validationNote
) {
}
