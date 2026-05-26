package com.stokr.admin.signal;

import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminSignalCleanupService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final StrategySignalRepository signalRepo;

    public record DateRange(Instant from, Instant toExclusive, LocalDate fromDate, LocalDate toDate) {}

    public DateRange resolveRange(LocalDate fromDate, LocalDate toDate) {
        LocalDate from = fromDate != null ? fromDate : LocalDate.now(IST);
        LocalDate to = toDate != null ? toDate : from;
        if (to.isBefore(from)) {
            to = from;
        }
        Instant fromInstant = from.atStartOfDay(IST).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(IST).toInstant();
        return new DateRange(fromInstant, toExclusive, from, to);
    }

    public String normalizeStrategyKey(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank() || "ALL".equalsIgnoreCase(strategyKey.trim())) {
            return null;
        }
        return strategyKey.trim();
    }

    @Transactional(readOnly = true)
    public long countMatching(DateRange range, String strategyKey, boolean includeReplayAndLab) {
        return signalRepo.countForCleanup(
                range.from(), range.toExclusive(), strategyKey, includeReplayAndLab);
    }

    @Transactional
    public AdminSignalCleanupResultDto cleanup(
            LocalDate fromDate,
            LocalDate toDate,
            String strategyKey,
            boolean includeReplayAndLab,
            boolean dryRun
    ) {
        DateRange range = resolveRange(fromDate, toDate);
        String normalizedKey = normalizeStrategyKey(strategyKey);
        long matched = countMatching(range, normalizedKey, includeReplayAndLab);
        long deleted = 0;
        if (!dryRun && matched > 0) {
            deleted = signalRepo.softDeleteForCleanup(
                    range.from(), range.toExclusive(), normalizedKey, includeReplayAndLab);
        }
        return new AdminSignalCleanupResultDto(
                dryRun,
                range.fromDate(),
                range.toDate(),
                normalizedKey != null ? normalizedKey : "ALL",
                includeReplayAndLab,
                matched,
                deleted,
                range.from(),
                range.toExclusive()
        );
    }

    @Transactional(readOnly = true)
    public List<AdminSignalStrategyStatsDto> statsByStrategy(
            LocalDate fromDate,
            LocalDate toDate,
            String strategyKey,
            boolean includeReplayAndLab
    ) {
        DateRange range = resolveRange(fromDate, toDate);
        String normalizedKey = normalizeStrategyKey(strategyKey);
        List<Object[]> rows = signalRepo.computeStatsByStrategy(
                range.from(), range.toExclusive(), normalizedKey, includeReplayAndLab);
        List<AdminSignalStrategyStatsDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new AdminSignalStrategyStatsDto(
                    r[0] != null ? r[0].toString() : "UNKNOWN",
                    toLong(r[1]),
                    toLong(r[2]),
                    toLong(r[3]),
                    toLong(r[4]),
                    toLong(r[5]),
                    toLong(r[6]),
                    toLong(r[7]),
                    toLong(r[8])
            ));
        }
        return out;
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        return ((Number) v).longValue();
    }
}
