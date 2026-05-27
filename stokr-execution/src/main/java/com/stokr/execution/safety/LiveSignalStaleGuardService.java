package com.stokr.execution.safety;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class LiveSignalStaleGuardService {

    private static final Set<String> SCALP_STRATEGIES = Set.of(
            "NSE_SPIKE_DETECTION", "VWAP_BOUNCE", "S3_VWAP_RETEST", "S7_RANGE_FADE");

    private static final Set<String> MOMENTUM_STRATEGIES = Set.of(
            "EARLY_BREAKOUT", "INDEX_HUNT", "GAP_FILL", "ADV_CASH", "SECTOR_LAGGARD");

    @Value("${stokr.oms.stale-signal.scalp-max-seconds:30}")
    private long scalpMaxSeconds;

    @Value("${stokr.oms.stale-signal.momentum-max-seconds:120}")
    private long momentumMaxSeconds;

    @Value("${stokr.oms.stale-signal.default-max-seconds:60}")
    private long defaultMaxSeconds;

    public Optional<OmsSafetyViolation> check(String strategyKey, Instant signalGeneratedAt, Instant now) {
        if (signalGeneratedAt == null) {
            return Optional.of(new OmsSafetyViolation(
                    "STALE_SIGNAL_UNKNOWN",
                    "Signal timestamp missing — LIVE blocked"));
        }
        long ageSec = Math.max(0, Duration.between(signalGeneratedAt, now).getSeconds());
        long threshold = thresholdSeconds(strategyKey);
        if (ageSec > threshold) {
            return Optional.of(new OmsSafetyViolation(
                    "STALE_SIGNAL",
                    "Signal age " + ageSec + "s exceeds LIVE threshold " + threshold + "s"));
        }
        return Optional.empty();
    }

    public long thresholdSeconds(String strategyKey) {
        String k = strategyKey == null ? "" : strategyKey.trim().toUpperCase(Locale.ROOT);
        if (SCALP_STRATEGIES.contains(k)) {
            return scalpMaxSeconds;
        }
        if (MOMENTUM_STRATEGIES.contains(k)) {
            return momentumMaxSeconds;
        }
        return defaultMaxSeconds;
    }

    public record OmsSafetyViolation(String code, String message) {}
}
