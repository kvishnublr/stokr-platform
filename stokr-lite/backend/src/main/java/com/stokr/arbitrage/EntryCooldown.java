package com.stokr.arbitrage;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One entry per strategy per underlying per window, whatever the strikes. Strike-keyed
 * cooldowns let the same view be doubled a minute later 100 points away (21-Oct-2026:
 * two BANKNIFTY Jade Lizards 62 s apart). Reads from the DB so it survives restarts.
 */
public final class EntryCooldown {
    public static final Duration WINDOW = Duration.ofMinutes(15);

    private EntryCooldown() {}

    /** True if {@code recent} holds an entry of this strategy on this underlying (same paper/live side) within WINDOW. */
    public static boolean blocked(List<LivePosition> recent, String strategyType, String underlying,
                                  boolean paper, LocalDateTime now) {
        String strat = OptionArbAutoExecService.normalizeStrategyId(strategyType);
        LocalDateTime since = now.minus(WINDOW);
        for (LivePosition p : recent) {
            if (p.getEnteredAt() == null || p.getEnteredAt().isBefore(since)) continue;
            if ("VOID".equals(p.getStatus())) continue;
            boolean pPaper = p.getBroker() == null || "PAPER".equalsIgnoreCase(p.getBroker());
            if (pPaper != paper) continue;
            if (underlying == null || !underlying.equalsIgnoreCase(p.getUnderlying())) continue;
            if (strat != null && strat.equals(OptionArbAutoExecService.normalizeStrategyId(p.getStrategyType()))) return true;
        }
        return false;
    }
}
