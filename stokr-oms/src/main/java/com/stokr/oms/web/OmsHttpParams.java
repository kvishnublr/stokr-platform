package com.stokr.oms.web;

import com.stokr.common.exception.BadRequestException;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.query.OmsReadParams;
import com.stokr.oms.query.PipelineMode;

import java.time.Instant;

public final class OmsHttpParams {

    private OmsHttpParams() {
    }

    public static OmsReadParams parse(
            String symbol,
            String strategyKey,
            String brokerVendor,
            String state,
            String executionMode,
            Instant fromInclusive,
            Instant toExclusive,
            String pipelineMode
    ) {
        OrderState st = null;
        if (state != null && !state.isBlank()) {
            try {
                st = OrderState.valueOf(state.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Invalid order state: " + state);
            }
        }
        ExecutionMode em = null;
        if (executionMode != null && !executionMode.isBlank()) {
            try {
                em = ExecutionMode.valueOf(executionMode.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Invalid execution mode: " + executionMode);
            }
        }
        PipelineMode pm = PipelineMode.ALL;
        if (pipelineMode != null && !pipelineMode.isBlank()) {
            try {
                pm = PipelineMode.valueOf(pipelineMode.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Invalid pipeline mode (ALL, LIVE, BACKTEST): " + pipelineMode);
            }
        }
        return new OmsReadParams(symbol, strategyKey, brokerVendor, st, em, fromInclusive, toExclusive, pm);
    }
}
