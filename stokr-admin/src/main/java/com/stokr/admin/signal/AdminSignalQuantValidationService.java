package com.stokr.admin.signal;

import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalProvenance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AdminSignalQuantValidationService {

    private static final int MIN_PRODUCTION_SAMPLE = 30;

    private final StrategySignalRepository signalRepository;

    public AdminSignalQuantValidationDto validate(AdminSignalStatsDto stats, Instant since) {
        long replayTagged = signalRepository.countBySignalSourceAndDeletedFalse(SignalProvenance.REPLAY);
        long labTagged = signalRepository.countBySignalSourceAndDeletedFalse(SignalProvenance.LAB);
        long productionSample = stats != null ? stats.totalAllTime() : 0;
        long resolved = stats != null ? stats.targetHit() + stats.slHit() : 0;
        double winRate = stats != null ? stats.winRate() : 0.0;

        boolean sampleAdequate = productionSample >= MIN_PRODUCTION_SAMPLE;
        boolean replayIsolated = replayTagged > 0 && productionSample < replayTagged;
        boolean statsUsable = sampleAdequate && resolved >= 10;

        String note;
        if (!replayIsolated && replayTagged > 0) {
            note = "Replay rows still present; ensure dashboards exclude REPLAY/LAB.";
        } else if (!sampleAdequate) {
            note = "Production sample too small for statistical validation (need >= " + MIN_PRODUCTION_SAMPLE + ").";
        } else if (!statsUsable) {
            note = "Insufficient resolved outcomes for expectancy (need >= 10 target/SL hits).";
        } else {
            note = "Production analytics sample is usable; winRate=" + String.format("%.1f", winRate) + "%.";
        }

        return new AdminSignalQuantValidationDto(
                productionSample,
                replayTagged,
                labTagged,
                replayIsolated,
                sampleAdequate,
                statsUsable,
                note
        );
    }
}
