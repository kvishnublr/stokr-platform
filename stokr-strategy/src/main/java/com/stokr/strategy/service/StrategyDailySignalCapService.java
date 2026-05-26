package com.stokr.strategy.service;

import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Caps production signal volume per strategy per IST trading day.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyDailySignalCapService {

    private final StrategySignalRepository signalRepository;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.daily-cap.enabled:true}")
    private boolean enabled;

    @Value("${stokr.strategy.daily-cap.default:120}")
    private int defaultCap;

    @Value("#{${stokr.strategy.daily-cap.per-strategy:{}}}")
    private Map<String, Integer> perStrategy;

    public boolean isOverCap(String strategyKey, Instant now) {
        if (!enabled || strategyKey == null || strategyKey.isBlank()) {
            return false;
        }
        int cap = perStrategy.getOrDefault(strategyKey, defaultCap);
        if (cap <= 0) {
            return false;
        }
        Instant dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        long count = signalRepository.countProductionSignalsForStrategySince(strategyKey, dayStart);
        if (count >= cap) {
            log.debug("signal.daily_cap_reached strategy={} count={} cap={}", strategyKey, count, cap);
            return true;
        }
        return false;
    }
}
