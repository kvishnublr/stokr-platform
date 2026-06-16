package com.stokr.common.correlation;

public final class CorrelationIdHolder {

    private static final ThreadLocal<String> ID = new ThreadLocal<>();

    private CorrelationIdHolder() {
    }

    public static void set(String correlationId) {
        ID.set(correlationId);
    }

    public static String get() {
        return ID.get();
    }

    public static void clear() {
        ID.remove();
    }
}
