package com.stokr.bootstrap.recovery;

/**
 * Classified operational failure modes for ranked auto-recovery.
 */
public enum OperationalFailureSignature {
    BROKER_FEED_DOWN,
    KILL_SWITCH_ACTIVE,
    SCANNER_STALLED,
    DB_UNREACHABLE,
    REDIS_UNREACHABLE,
    BAD_AUTH,
    UNKNOWN
}
