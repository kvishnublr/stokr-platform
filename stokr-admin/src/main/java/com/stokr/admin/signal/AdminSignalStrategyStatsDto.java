package com.stokr.admin.signal;

public record AdminSignalStrategyStatsDto(
        String strategyName,
        long total,
        long buyCount,
        long sellCount,
        long targetHit,
        long slHit,
        long running,
        long expired,
        long pending
) {
    public double winRate() {
        long resolved = targetHit + slHit;
        return resolved == 0 ? 0.0 : (double) targetHit / resolved * 100.0;
    }
}
