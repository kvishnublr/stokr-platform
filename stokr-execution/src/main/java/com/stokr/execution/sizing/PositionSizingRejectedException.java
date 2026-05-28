package com.stokr.execution.sizing;

public class PositionSizingRejectedException extends RuntimeException {
    public PositionSizingRejectedException(String reason) {
        super(reason);
    }
}
