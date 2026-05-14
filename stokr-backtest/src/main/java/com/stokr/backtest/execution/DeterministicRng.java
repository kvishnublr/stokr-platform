package com.stokr.backtest.execution;

/**
 * Seeded deterministic RNG for future fill / partial-fill simulation (PR-3).
 */
public final class DeterministicRng {

    private DeterministicRng() {
    }

    public static double nextUnit(long seed, int barIndex, int salt) {
        java.util.Random r = new java.util.Random(mix(seed, barIndex, salt));
        return r.nextDouble();
    }

    private static long mix(long seed, int barIndex, int salt) {
        long x = seed ^ ((long) barIndex << 32) ^ (long) salt * 0x9E3779B97F4A7C15L;
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        return x;
    }
}
