package com.stokr.admin.domain;

public enum MarketBackfillSymbolStatus {
    PENDING,
    FETCHED,
    GAP_DETECTED,
    REPAIRED,
    FAILED,
    SKIPPED
}
