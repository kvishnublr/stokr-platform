package com.stokr.oms.metrics;

import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/** Derive fill diagnostics when live broker sync did not persist latency/slippage/spread. */
public final class ExecutionMetricsHelper {

    private ExecutionMetricsHelper() {
    }

    public static Long latencyMs(Instant orderCreatedAt, Instant fillTime) {
        if (orderCreatedAt == null || fillTime == null) {
            return null;
        }
        long ms = Duration.between(orderCreatedAt, fillTime).toMillis();
        return Math.max(0L, ms);
    }

    public static BigDecimal resolveReferencePrice(OmsOrder order, OmsExecution execution) {
        if (order == null) {
            return execution != null ? execution.getReferencePrice() : null;
        }
        if (order.getEntryReferencePrice() != null && order.getEntryReferencePrice().compareTo(BigDecimal.ZERO) > 0) {
            return order.getEntryReferencePrice();
        }
        if (order.getLimitPrice() != null && order.getLimitPrice().compareTo(BigDecimal.ZERO) > 0) {
            return order.getLimitPrice();
        }
        if (execution != null && execution.getReferencePrice() != null
                && execution.getReferencePrice().compareTo(BigDecimal.ZERO) > 0) {
            return execution.getReferencePrice();
        }
        return execution != null ? execution.getAvgPrice() : null;
    }

    public static BigDecimal slippageBps(BigDecimal fillPrice, BigDecimal referencePrice, String side) {
        if (fillPrice == null || referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal diff = fillPrice.subtract(referencePrice);
        if ("SELL".equalsIgnoreCase(side)) {
            diff = diff.negate();
        }
        return diff.divide(referencePrice, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(10_000))
                .setScale(4, RoundingMode.HALF_UP);
    }

    public static Long resolveLatencyMs(OmsOrder order, OmsExecution execution) {
        if (execution == null) {
            return null;
        }
        if (execution.getLatencyMs() != null) {
            return execution.getLatencyMs();
        }
        Instant fill = execution.getFillTime() != null ? execution.getFillTime() : execution.getExecutionTimestamp();
        return latencyMs(order != null ? order.getCreatedAt() : null, fill);
    }

    public static BigDecimal resolveSlippageBps(OmsOrder order, OmsExecution execution) {
        if (execution == null) {
            return null;
        }
        if (execution.getSlippageBps() != null) {
            return execution.getSlippageBps();
        }
        BigDecimal ref = resolveReferencePrice(order, execution);
        return slippageBps(execution.getAvgPrice(), ref, order != null ? order.getSide() : null);
    }

    public static BigDecimal resolveSpreadBps(OmsExecution execution, BigDecimal liveDefaultSpreadBps) {
        if (execution == null) {
            return null;
        }
        if (execution.getSpreadBps() != null) {
            return execution.getSpreadBps();
        }
        String kind = execution.getExecutionKind();
        if (kind == null) {
            return null;
        }
        if ("LIVE_BROKER".equalsIgnoreCase(kind) && liveDefaultSpreadBps != null) {
            return liveDefaultSpreadBps;
        }
        if ("SIM".equalsIgnoreCase(kind)) {
            return BigDecimal.valueOf(1.0);
        }
        return null;
    }
}
