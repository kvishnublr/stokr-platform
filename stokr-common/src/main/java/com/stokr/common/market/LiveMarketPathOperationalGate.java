package com.stokr.common.market;

import java.time.Instant;

/**
 * Optional monolith hook: when non-operational, live strategy poll loops should pause to avoid silent evaluation on stale rails.
 */
@FunctionalInterface
public interface LiveMarketPathOperationalGate {

    LiveMarketPathAssessment assess(Instant now);
}
