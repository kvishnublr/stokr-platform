package com.stokr.admin.domain;

public enum MarketBackfillJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED
}
