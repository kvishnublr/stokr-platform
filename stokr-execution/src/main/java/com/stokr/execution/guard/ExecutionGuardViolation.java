package com.stokr.execution.guard;

public record ExecutionGuardViolation(
        String code,
        ExecutionGuardSeverity severity,
        String message,
        String detail
) {
}

