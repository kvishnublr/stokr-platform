package com.stokr.admin.signal;

public record AdminSignalStatsDto(
        long totalToday,
        long buyToday,
        long sellToday,
        long liveToday,
        long paperToday,
        Double avgConfidence,
        long targetHit,
        long slHit,
        long running,
        long expired,
        long protectedExit,
        long pending,
        long totalAllTime
) {
    public double winRate() {
        long resolved = targetHit + slHit;
        return resolved == 0 ? 0.0 : (double) targetHit / resolved * 100.0;
    }
}
