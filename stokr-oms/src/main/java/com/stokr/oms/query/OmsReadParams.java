package com.stokr.oms.query;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OrderState;

import java.time.Instant;

/**
 * Shared filter shape for OMS read APIs. Time window semantics depend on the endpoint:
 * orders filter on {@code oms_orders.created_at}; executions on execution event time
 * (coalesce of execution_timestamp, fill_time, created_at); trades on {@code oms_trades.created_at}.
 */
public record OmsReadParams(
        String symbol,
        String strategyKey,
        String brokerVendor,
        OrderState state,
        ExecutionMode executionMode,
        Instant fromInclusive,
        Instant toExclusive,
        PipelineMode pipelineMode
) {
}
