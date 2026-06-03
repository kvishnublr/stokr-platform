package com.stokr.bootstrap.recovery;

/**
 * Deterministic recovery actions executed by the orchestrator (one per cycle).
 */
public enum RecoveryActionType {
    NONE,
    REFRESH_BROKER_TOKENS,
    RECONNECT_BROKER_WS,
    DEACTIVATE_KILL_SWITCH,
    ACTIVATE_SIGNAL_PIPELINE,
    HEAL_DB_POOL,
    HEAL_REDIS,
    ESCALATE_HUMAN,
    REQUEST_CONTAINER_RESTART
}
