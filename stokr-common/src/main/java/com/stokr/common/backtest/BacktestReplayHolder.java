package com.stokr.common.backtest;

/**
 * Thread-local flag set by the backtest replay engine for the duration of each bar evaluation.
 * Allows integrity gates and session guards to bypass live-feed checks during historical replay.
 */
public final class BacktestReplayHolder {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private BacktestReplayHolder() {}

    public static void activate() {
        ACTIVE.set(Boolean.TRUE);
    }

    public static void deactivate() {
        ACTIVE.remove();
    }

    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }
}
