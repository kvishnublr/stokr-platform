package com.stokr.strategy.service;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Suppresses duplicate live signals for the same strategy + symbol + direction within a cooldown
 * window, and re-entries on a symbol too soon after a position there was just exited
 * (entry/exit flip-flop churn pays spread + charges repeatedly on the same idea).
 */
@Service
@RequiredArgsConstructor
public class SignalEmissionGuardService {

    private final StrategySignalRepository signalRepository;

    @Value("${stokr.strategy.signal-dedup.cooldown-minutes:15}")
    private int cooldownMinutes;

    @Value("${stokr.strategy.signal-dedup.post-exit-cooldown-minutes:15}")
    private int postExitCooldownMinutes;

    public boolean shouldSuppress(StrategySignalEntity signal) {
        if (signal == null || Boolean.TRUE.equals(signal.getTestTrade())) {
            return false;
        }
        if (signal.getReason() != null && signal.getReason().startsWith("ADMIN_REGENERATE:")) {
            return false;
        }
        if (signal.getBacktestRunId() != null) {
            return false;
        }
        String strategy = signal.getStrategyName();
        String symbol = signal.getSymbol();
        if (strategy == null || symbol == null || signal.getSignalType() == null) {
            return false;
        }
        Instant now = Instant.now();
        if (signalRepository.existsSimilarLiveSignal(
                strategy, symbol, signal.getSignalType(),
                now.minus(cooldownMinutes, ChronoUnit.MINUTES))) {
            return true;
        }
        return postExitCooldownMinutes > 0 && signalRepository.existsRecentlyExitedSignal(
                strategy, symbol, now.minus(postExitCooldownMinutes, ChronoUnit.MINUTES));
    }
}
