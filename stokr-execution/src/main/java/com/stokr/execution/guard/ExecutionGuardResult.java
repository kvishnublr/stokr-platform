package com.stokr.execution.guard;

import java.util.List;

public record ExecutionGuardResult(
        ExecutionGuardOutcome outcome,
        ExecutionGuardPolicy policy,
        MarketSnapshot market,
        List<ExecutionGuardViolation> violations,
        long validationLatencyMs
) {
    public boolean allowExecution() {
        return outcome == ExecutionGuardOutcome.PASS || outcome == ExecutionGuardOutcome.SOFT_FAIL;
    }
}

