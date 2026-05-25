package com.stokr.strategy.service;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Suppresses duplicate live signals for the same strategy + symbol + direction within a cooldown window.
 */
@Service
@RequiredArgsConstructor
public class SignalEmissionGuardService {

    private final StrategySignalRepository signalRepository;

    @Value("${stokr.strategy.signal-dedup.cooldown-minutes:15}")
    private int cooldownMinutes;

    public boolean shouldSuppress(StrategySignalEntity signal) {
        if (signal == null || Boolean.TRUE.equals(signal.getTestTrade())) {
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
        Instant since = Instant.now().minus(cooldownMinutes, ChronoUnit.MINUTES);
        return signalRepository.existsSimilarLiveSignal(
                strategy, symbol, signal.getSignalType(), since);
    }
}
