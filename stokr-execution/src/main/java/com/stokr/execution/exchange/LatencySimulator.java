package com.stokr.execution.exchange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Simulates realistic execution latency.
 * Latency includes:
 * - Order queue delay (time to enter order book)
 * - Exchange processing (time for matching)
 * - Network round-trip (propagation back to client)
 *
 * Distribution: Normal/Gaussian around configured mean.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LatencySimulator {

    @Value("${stokr.simulation.latency-ms:25}")
    private long baseLatencyMs;

    @Value("${stokr.simulation.order-queue-delay-ms:0}")
    private long orderQueueDelayMs;

    private final Random random = new Random();

    /**
     * Get simulated latency for an order fill.
     * Returns latency in milliseconds with realistic distribution.
     */
    public long getLatency() {
        // Queue delay: uniform distribution [0, orderQueueDelayMs]
        long queueDelayMs = orderQueueDelayMs > 0
                ? random.nextLong() % (orderQueueDelayMs + 1)
                : 0;

        // Exchange processing: normal distribution around baseLatencyMs
        // With standard deviation of ~40% of mean
        double processingDelayMs = baseLatencyMs + (random.nextGaussian() * (baseLatencyMs * 0.4));
        processingDelayMs = Math.max(0, processingDelayMs);  // Can't be negative

        // Network round-trip: small, ~2-5ms
        long networkDelayMs = 2 + random.nextInt(4);

        long totalLatencyMs = queueDelayMs + Math.round(processingDelayMs) + networkDelayMs;
        return Math.max(1, totalLatencyMs);  // At least 1ms
    }

    /**
     * Get latency with explicit components (for testing/control).
     */
    public long getLatency(long queueDelayMs, long exchangeDelayMs, long networkDelayMs) {
        // Add randomness to each component
        long queueActual = queueDelayMs + random.nextLong() % (Math.max(1, queueDelayMs / 2));
        long exchangeActual = exchangeDelayMs + (long)(random.nextGaussian() * (exchangeDelayMs * 0.3));
        long networkActual = networkDelayMs + random.nextLong() % (Math.max(1, networkDelayMs / 2));

        return Math.max(1, queueActual + Math.max(0, exchangeActual) + networkActual);
    }

    /**
     * Get fast latency (1-3ms) for limit orders in liquid market.
     */
    public long getFastLatency() {
        return 1 + random.nextInt(3);
    }

    /**
     * Get slow latency (50-150ms) for large or market orders.
     */
    public long getSlowLatency() {
        return 50 + random.nextInt(100);
    }

    /**
     * Get latency with potential slippage (network delay, queue buildup).
     * Used when market is congested.
     */
    public long getCongestedLatency() {
        // Mean: baseLatencyMs * 2, higher variance
        double congestedDelayMs = (baseLatencyMs * 2) + (random.nextGaussian() * baseLatencyMs);
        return Math.max(1, Math.round(congestedDelayMs));
    }

    /**
     * Set base latency for configuration.
     */
    public void setBaseLatencyMs(long ms) {
        this.baseLatencyMs = ms;
    }

    /**
     * Set order queue delay for configuration.
     */
    public void setOrderQueueDelayMs(long ms) {
        this.orderQueueDelayMs = ms;
    }
}
