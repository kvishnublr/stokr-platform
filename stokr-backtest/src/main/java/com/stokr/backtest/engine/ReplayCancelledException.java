package com.stokr.backtest.engine;

/**
 * Thrown when a cooperative cancel is observed mid-replay. The run is left resumable (status stays {@code RUNNING}).
 */
public class ReplayCancelledException extends RuntimeException {

    public ReplayCancelledException() {
        super("Replay cancelled by user");
    }
}
