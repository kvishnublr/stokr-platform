package com.stokr.strategy.sdk;

import java.time.Instant;

/**
 * Wall-clock abstraction — strategies must use this instead of {@link Instant#now()} for replay determinism.
 */
@FunctionalInterface
public interface DeterministicClock {

    Instant now();
}
