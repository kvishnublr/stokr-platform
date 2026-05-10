package com.stokr.common.pipeline;

public final class PipelineQueues {

    public static final String STRATEGY_SIGNAL = "strategy.signal.queue";
    public static final String STRATEGY_SIGNAL_DLQ = "strategy.signal.dlq";

    public static final String OMS_ORDER = "oms.order.queue";
    public static final String OMS_ORDER_DLQ = "oms.order.dlq";

    public static final String EXECUTION = "execution.queue";
    public static final String EXECUTION_DLQ = "execution.dlq";

    private PipelineQueues() {
    }
}
