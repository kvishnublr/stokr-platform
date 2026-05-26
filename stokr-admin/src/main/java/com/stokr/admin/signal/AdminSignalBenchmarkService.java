package com.stokr.admin.signal;

import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.service.SignalHistoricalReplayService;
import com.stokr.strategy.service.SignalOutcomeTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminSignalBenchmarkService {

    private final AdminSignalCleanupService cleanupService;
    private final SignalHistoricalReplayService historicalReplayService;
    private final SignalOutcomeTrackerService outcomeTrackerService;
    private final StrategyDefinitionRepository strategyDefinitionRepository;

    public AdminSignalBenchmarkResultDto rerun(
            LocalDate fromDate,
            LocalDate toDate,
            String strategyKey,
            boolean purgeBeforeRerun,
            boolean includeReplayAndLab
    ) {
        LocalDate from = fromDate;
        LocalDate to = toDate;
        String normalizedKey = cleanupService.normalizeStrategyKey(strategyKey);
        long purgedCount = 0;

        if (purgeBeforeRerun) {
            AdminSignalCleanupResultDto purge = cleanupService.cleanup(
                    from, to, strategyKey, includeReplayAndLab, false);
            purgedCount = purge.deletedCount();
        }

        List<String> keysToReplay = resolveStrategyKeys(normalizedKey);
        Map<String, Object> replayDetails = new LinkedHashMap<>();
        int totalSignals = 0;

        for (String key : keysToReplay) {
            try {
                SignalHistoricalReplayService.ReplayResult result =
                        historicalReplayService.replay(key, from, to);
                totalSignals += result.signalsGenerated();
                replayDetails.put(key, Map.of(
                        "symbolsScanned", result.symbolsScanned(),
                        "barsProcessed", result.barsProcessed(),
                        "signalsGenerated", result.signalsGenerated()
                ));
            } catch (Exception ex) {
                log.error("benchmark.replay_failed strategyKey={} {}", key, ex.getMessage(), ex);
                replayDetails.put(key, Map.of("error", ex.getMessage()));
            }
        }

        int outcomesProcessed = outcomeTrackerService.trackAllPending();
        List<AdminSignalStrategyStatsDto> stats = cleanupService.statsByStrategy(
                from, to, strategyKey, includeReplayAndLab);

        return new AdminSignalBenchmarkResultDto(
                "COMPLETED",
                normalizedKey != null ? normalizedKey : "ALL",
                from,
                to,
                purgeBeforeRerun,
                purgedCount,
                keysToReplay.size(),
                totalSignals,
                outcomesProcessed,
                stats,
                replayDetails
        );
    }

    private List<String> resolveStrategyKeys(String normalizedKey) {
        if (normalizedKey != null) {
            return List.of(normalizedKey);
        }
        List<String> keys = new ArrayList<>();
        strategyDefinitionRepository.findAll().stream()
                .filter(d -> !d.isDeleted() && d.isEnabled())
                .map(d -> d.getStrategyKey())
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(keys::add);
        return keys;
    }
}
