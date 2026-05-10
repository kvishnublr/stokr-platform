package com.stokr.oms.domain;

public enum OrderState {
    CREATED,
    VALIDATING,
    RISK_CHECK,
    ACCEPTED,
    QUEUED,
    SENT,
    ACKNOWLEDGED,
    PARTIAL_FILL,
    FILLED,
    REJECTED,
    FAILED,
    CANCELLED
}
