package com.stokr.common.simulation;

/**
 * Named E2E scenarios exercised through the production pipeline.
 */
public enum SimulationScenario {
    GAP_FILL_WIN,
    GAP_FILL_LOSS,
    VWAP_BOUNCE_WIN,
    VWAP_BOUNCE_LOSS,
    NSE_SPIKE_WIN,
    PROTECTION_EXIT,
    FEED_FAILURE,
    BROKER_REJECT,
    TARGET_HIT,
    SL_HIT,
    CUSTOM
}
