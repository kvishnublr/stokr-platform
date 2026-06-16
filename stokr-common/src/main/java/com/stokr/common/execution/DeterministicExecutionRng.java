package com.stokr.common.execution;

/**
 * Seeded deterministic RNG for execution simulation (fills, rejections, latency draws).
 * No wall-clock dependency.
 */
public final class DeterministicExecutionRng {

    private DeterministicExecutionRng() {
    }

    public static double nextUnit(long seed, long saltA, int saltB) {
        java.util.Random r = new java.util.Random(mix(seed, saltA, saltB));
        return r.nextDouble();
    }

    public static long nextLong(long seed, long saltA, int saltB) {
        return mix(seed, saltA, saltB);
    }

    private static long mix(long seed, long saltA, int saltB) {
        long x = seed ^ saltA ^ ((long) saltB * 0x9E3779B97F4A7C15L);
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        return x;
    }
}
