package com.stokr.execution.broker;

public enum BrokerPositionTruthSyncState {
    VERIFIED,
    PENDING_SYNC,
    RECONCILING,
    STALE,
    MISMATCH,
    BROKER_CONFLICT
}
