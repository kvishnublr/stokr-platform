package com.stokr.strategy.lifecycle;

/**
 * Observability sub-triggers for {@link ExitCategory#PRESSURE_EXIT}.
 */
public enum PressureExitTrigger {
    IMBALANCE_COLLAPSE,
    MOMENTUM_REVERSAL,
    SPREAD_WIDENING,
    VOLUME_VACUUM,
    ADVERSE_VELOCITY,
    TRAILING_BREAKEVEN
}
