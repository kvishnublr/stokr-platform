package com.stokr.oms.journal;

import java.util.UUID;

public final class StreamKeys {

    public static final String ST_ORDER = "ORDER";
    public static final String ST_USER = "USER";
    public static final String ST_BACKTEST = "BACKTEST";
    public static final String ST_STRATEGY_INSTANCE = "STRATEGY_INSTANCE";

    private StreamKeys() {
    }

    public static String order(UUID orderId) {
        return orderId.toString();
    }

    public static String user(UUID userId) {
        return userId.toString();
    }

    public static String backtest(UUID runId) {
        return runId.toString();
    }

    public static String strategyInstance(UUID instanceId) {
        return instanceId.toString();
    }
}
