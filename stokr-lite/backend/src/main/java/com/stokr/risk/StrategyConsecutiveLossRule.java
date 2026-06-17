package com.stokr.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Strategy consecutive loss rule.
 * Pauses a strategy after 3 consecutive losses to prevent drawdown spirals.
 */
@Component
public class StrategyConsecutiveLossRule implements RiskRule {

    @Value("${trading.max-consecutive-losses:3}")
    private int maxConsecutiveLosses;

    // Key: userId_strategyType -> consecutive loss count
    private final Map<String, AtomicInteger> lossCounters = new ConcurrentHashMap<>();

    @Override
    public String getRuleName() {
        return "STRATEGY_CONSECUTIVE_LOSS";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        // Note: RiskContext doesn't have strategy type. This rule is checked
        // at a higher level where strategy type is known. For now we pass.
        return RiskDecision.pass();
    }

    /**
     * Check if a strategy is paused for a given user.
     */
    public RiskDecision checkStrategy(Long userId, String strategyType) {
        String key = userId + "_" + strategyType;
        AtomicInteger count = lossCounters.get(key);
        if (count != null && count.get() >= maxConsecutiveLosses) {
            return RiskDecision.fail(String.format(
                    "Strategy %s paused after %d consecutive losses",
                    strategyType, count.get()));
        }
        return RiskDecision.pass();
    }

    /**
     * Record a loss for a strategy.
     */
    public void recordLoss(Long userId, String strategyType) {
        String key = userId + "_" + strategyType;
        lossCounters.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * Record a win — resets the loss counter.
     */
    public void recordWin(Long userId, String strategyType) {
        String key = userId + "_" + strategyType;
        lossCounters.remove(key);
    }

    /**
     * Reset all counters (e.g., at market open).
     */
    public void resetAll() {
        lossCounters.clear();
    }
}
