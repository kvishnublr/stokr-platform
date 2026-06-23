package com.stokr.admin;

import com.stokr.engine.SignalEntity;
import com.stokr.engine.SignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartinkLiveDataService {

    private final SignalRepository signalRepository;

    public Map<String, Object> getLiveChartinkData() {
        Instant thirtyMinutesAgo = Instant.now().minusSeconds(30 * 60);

        // Fetch all signals from Chartink in last 30 min
        List<SignalEntity> recentSignals = signalRepository.findTop50ByOrderByCreatedAtDesc()
                .stream()
                .filter(s -> s.getSource() == SignalEntity.SignalSource.CHARTINK)
                .filter(s -> s.getCreatedAt().isAfter(thirtyMinutesAgo))
                .collect(Collectors.toList());

        // Group by scanner name
        Map<String, List<SignalEntity>> byScanner = recentSignals.stream()
                .collect(Collectors.groupingBy(s -> s.getScannerName() != null ? s.getScannerName() : "Unknown"));

        // Calculate metrics per scanner
        Map<String, Map<String, Object>> scannerMetrics = new HashMap<>();
        for (Map.Entry<String, List<SignalEntity>> entry : byScanner.entrySet()) {
            String scanner = entry.getKey();
            List<SignalEntity> signals = entry.getValue();

            long executed = signals.stream().filter(s -> "EXECUTED".equals(s.getStatus())).count();
            long rejected = signals.stream().filter(s -> "REJECTED".equals(s.getStatus())).count();
            long generated = signals.stream().filter(s -> "GENERATED".equals(s.getStatus())).count();

            long targetHit = signals.stream().filter(s -> "TARGET_HIT".equals(s.getExitType())).count();
            long slHit = signals.stream().filter(s -> "SL_HIT".equals(s.getExitType())).count();

            double hitRate = executed > 0 ? (double) targetHit / executed * 100 : 0;
            double accuracy = (executed + rejected) > 0 ? (double) executed / (executed + rejected) * 100 : 0;

            scannerMetrics.put(scanner, Map.of(
                    "totalHits", signals.size(),
                    "executed", executed,
                    "rejected", rejected,
                    "pending", generated,
                    "targetHit", targetHit,
                    "slHit", slHit,
                    "hitRate", String.format("%.1f%%", hitRate),
                    "accuracy", String.format("%.1f%%", accuracy),
                    "lastSignal", signals.isEmpty() ? null : signals.get(0).getCreatedAt()
            ));
        }

        // Overall stats
        long totalHits = recentSignals.size();
        long totalExecuted = recentSignals.stream().filter(s -> "EXECUTED".equals(s.getStatus())).count();
        long totalRejected = recentSignals.stream().filter(s -> "REJECTED".equals(s.getStatus())).count();

        // Most active scanners (top 5)
        List<Map<String, Object>> topScanners = byScanner.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(5)
                .map(e -> {
                    Map<String, Object> metrics = scannerMetrics.get(e.getKey());
                    return Map.of(
                            "scanner", (Object) e.getKey(),
                            "hits", e.getValue().size(),
                            "accuracy", metrics.get("accuracy")
                    );
                })
                .collect(Collectors.toList());

        // Recent signal stream (last 20, reversed for newest first)
        List<Map<String, Object>> recentStream = recentSignals.stream()
                .limit(20)
                .map(s -> Map.of(
                        "id", (Object) s.getId(),
                        "symbol", s.getSymbol(),
                        "side", s.getSide().toString(),
                        "scanner", s.getScannerName() != null ? s.getScannerName() : "Unknown",
                        "status", s.getStatus(),
                        "confidence", s.getConfidence() != null ? s.getConfidence().doubleValue() : 0.0,
                        "reason", s.getReason() != null ? s.getReason() : "No reason",
                        "exitType", s.getExitType(),
                        "createdAt", s.getCreatedAt().toString()
                ))
                .collect(Collectors.toList());

        return Map.of(
                "timestamp", Instant.now().toString(),
                "timeframe", "30min",
                "overall", Map.of(
                        "totalSignals", totalHits,
                        "executed", totalExecuted,
                        "rejected", totalRejected,
                        "executionRate", String.format("%.1f%%", totalHits > 0 ? (double) totalExecuted / totalHits * 100 : 0)
                ),
                "scanners", scannerMetrics,
                "topScanners", topScanners,
                "recentSignals", recentStream
        );
    }
}
