package com.stokr.admin.signal;

public record AdminOmsStatsDto(
        long totalToday,
        long filledToday,
        long rejectedToday,
        long partialToday,
        long cancelledToday,
        long pendingToday,
        long totalAllTime
) {
    public double fillRate() {
        return totalToday == 0 ? 0.0 : (double) filledToday / totalToday * 100.0;
    }
}
