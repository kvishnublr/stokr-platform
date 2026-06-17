package com.stokr.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimum trade gap rule.
 * Prevents revenge trading by enforcing a minimum time gap between trades.
 * Default: 2 minutes.
 */
@Component
public class MinTradeGapRule implements RiskRule {

    @Value("${trading.min-trade-gap-minutes:2}")
    private int minTradeGapMinutes;

    private final Map<String, Long> lastTradeTimeMs = new ConcurrentHashMap<>();

    @Override
    public String getRuleName() {
        return "MIN_TRADE_GAP";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        String key = context.userId() + "_" + context.symbol();
        long now = System.currentTimeMillis();
        long minGapMs = minTradeGapMinutes * 60L * 1000L;

        Long last = lastTradeTimeMs.get(key);
        if (last != null && (now - last) < minGapMs) {
            long remainingSec = (minGapMs - (now - last)) / 1000;
            return RiskDecision.fail(String.format(
                    "Min trade gap not met for %s: %d seconds remaining (%d min rule)",
                    context.symbol(), remainingSec, minTradeGapMinutes));
        }

        // Record this trade attempt as the last trade time
        lastTradeTimeMs.put(key, now);
        return RiskDecision.pass();
    }

    /**
     * Called after a successful trade execution to record the actual trade time.
     */
    public void recordTrade(Long userId, String symbol) {
        lastTradeTimeMs.put(userId + "_" + symbol, System.currentTimeMillis());
    }
}
