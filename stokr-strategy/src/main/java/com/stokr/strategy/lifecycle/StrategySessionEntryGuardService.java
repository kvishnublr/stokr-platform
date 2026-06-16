package com.stokr.strategy.lifecycle;

import com.stokr.common.backtest.BacktestReplayHolder;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Hard session entry lock ??? one production signal per strategy+symbol per IST session.
 */
@Service
@RequiredArgsConstructor
public class StrategySessionEntryGuardService {

    private final StrategySignalRepository signalRepository;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    public boolean isSessionEntryAllowed(String strategyKey, String symbol, Instant asOf) {
        if (BacktestReplayHolder.isActive()) {
            return true;
        }
        StrategyLifecycleProfile profile = StrategyLifecycleProfile.forStrategy(strategyKey);
        if (profile.maxEntriesPerSymbolPerSession() <= 0) {
            return true;
        }
        Instant sessionStart = sessionStartInstant(asOf);
        long count = signalRepository.countProductionSignalsForStrategyAndSymbolSince(
                strategyKey, symbol, sessionStart);
        return count < profile.maxEntriesPerSymbolPerSession();
    }

    public boolean isReentryAllowed(String strategyKey) {
        return StrategyLifecycleProfile.forStrategy(strategyKey).allowReentry();
    }

    private Instant sessionStartInstant(Instant anchor) {
        LocalDate sessionDate = anchor.atZone(zone).toLocalDate();
        return ZonedDateTime.of(sessionDate, java.time.LocalTime.of(9, 15), zone).toInstant();
    }
}
