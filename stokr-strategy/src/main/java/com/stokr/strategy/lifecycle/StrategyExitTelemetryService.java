package com.stokr.strategy.lifecycle;

import com.stokr.strategy.domain.StrategySignalEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyExitTelemetryService {

    private final StrategyExitTelemetryRepository repository;

    @Transactional
    public void recordExit(
            StrategySignalEntity signal,
            ExitCategory category,
            String exitReason,
            Instant exitTime,
            BigDecimal pressureScoreAtExit,
            PressureExitTrigger pressureTrigger,
            boolean minHoldBypassed) {
        if (signal == null || category == null || exitTime == null) {
            return;
        }

        Instant entryTime = resolveEntryTime(signal);
        long holdSeconds = Math.max(0, ChronoUnit.SECONDS.between(entryTime, exitTime));

        StrategyExitTelemetry row = new StrategyExitTelemetry();
        row.setSignalId(signal.getId());
        row.setStrategyName(signal.getStrategyName() != null ? signal.getStrategyName() : "UNKNOWN");
        row.setSymbol(signal.getSymbol());
        row.setEntryTime(entryTime);
        row.setExitTime(exitTime);
        row.setHoldSeconds(holdSeconds);
        row.setExitCategory(category.name());
        row.setExitReason(exitReason != null ? exitReason : category.name());
        row.setUnrealizedPnlPeak(signal.getMaxFavorableExcursion());
        row.setUnrealizedPnlTrough(signal.getMaxAdverseExcursion());
        row.setPressureScoreAtExit(pressureScoreAtExit);
        row.setMinHoldBypassed(minHoldBypassed);
        row.setPressureTrigger(pressureTrigger != null ? pressureTrigger.name() : null);
        row.setCreatedAt(Instant.now());

        repository.save(row);

        log.info(
                "strategy.exit.telemetry strategy={} symbol={} category={} holdSec={} minHoldBypassed={} trigger={} reason={}",
                row.getStrategyName(),
                row.getSymbol(),
                row.getExitCategory(),
                row.getHoldSeconds(),
                row.isMinHoldBypassed(),
                row.getPressureTrigger(),
                row.getExitReason());
    }

    public static Instant resolveEntryTime(StrategySignalEntity signal) {
        if (signal.getCandleTimestamp() != null) {
            return signal.getCandleTimestamp();
        }
        if (signal.getCreatedAt() != null) {
            return signal.getCreatedAt();
        }
        return Instant.now();
    }

    public static long holdSeconds(StrategySignalEntity signal, Instant now) {
        return Math.max(0, ChronoUnit.SECONDS.between(resolveEntryTime(signal), now));
    }
}
