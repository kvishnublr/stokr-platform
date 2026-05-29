package com.stokr.admin.signal;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.lifecycle.StrategyExitTelemetry;
import com.stokr.strategy.lifecycle.StrategyExitTelemetryRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalProvenance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
@Service
@RequiredArgsConstructor
public class AdminSignalTruthDiagnosticsService {

    private final StrategySignalRepository signalRepository;
    private final StrategyExitTelemetryRepository exitTelemetryRepository;

    public AdminProtectionDiagnosticsDto protectionDiagnostics(Instant since) {
        Instant from = since != null ? since : Instant.now().minus(24, ChronoUnit.HOURS);
        Instant to = Instant.now();
        List<StrategyExitTelemetry> exits = exitTelemetryRepository.findAll().stream()
                .filter(e -> e.getCreatedAt() != null && !e.getCreatedAt().isBefore(from))
                .toList();

        Map<String, Long> byCategory = new LinkedHashMap<>();
        long prematureVacuum = 0;
        long minHoldBypassed = 0;
        double holdSum = 0;
        for (StrategyExitTelemetry e : exits) {
            byCategory.merge(e.getExitCategory(), 1L, Long::sum);
            holdSum += e.getHoldSeconds();
            if (e.isMinHoldBypassed()) {
                minHoldBypassed++;
            }
            if (isPrematureVolumeVacuum(e)) {
                prematureVacuum++;
            }
        }

        List<AdminProtectionDiagnosticsDto.ProtectionExitRow> recent = exits.stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(50)
                .map(e -> new AdminProtectionDiagnosticsDto.ProtectionExitRow(
                        e.getSignalId() != null ? e.getSignalId().toString() : null,
                        e.getStrategyName(),
                        e.getSymbol(),
                        e.getHoldSeconds(),
                        e.getExitCategory(),
                        e.getExitReason(),
                        e.isMinHoldBypassed(),
                        e.getPressureTrigger()
                ))
                .toList();

        double avgHold = exits.isEmpty() ? 0 : holdSum / exits.size();
        return new AdminProtectionDiagnosticsDto(
                from.toString(),
                to.toString(),
                exits.size(),
                prematureVacuum,
                minHoldBypassed,
                avgHold,
                byCategory,
                recent
        );
    }

    public AdminStrategyDiagnosticsDto strategyDiagnostics(Instant since) {
        Instant from = since != null ? since : Instant.now().minus(24, ChronoUnit.HOURS);
        Instant to = Instant.now();
        List<StrategySignalEntity> recent = signalRepository.findTop200ByDeletedFalseOrderByCreatedAtDesc().stream()
                .filter(s -> s.getCreatedAt() != null && !s.getCreatedAt().isBefore(from))
                .filter(s -> s.getSignalSource() == SignalProvenance.LIVE || s.getSignalSource() == SignalProvenance.PAPER)
                .toList();

        long confidenceNull = 0;
        long confidenceV2 = 0;
        BigDecimal confSum = BigDecimal.ZERO;
        BigDecimal probSum = BigDecimal.ZERO;
        int confCount = 0;
        Map<String, Long> byOwner = new LinkedHashMap<>();
        Map<String, Long> byLifecycle = new LinkedHashMap<>();
        Map<String, Long> byOutcome = new LinkedHashMap<>();

        for (StrategySignalEntity s : recent) {
            if (s.getConfidenceScore() == null) {
                confidenceNull++;
            }
            if ("CONFIDENCE_V2".equals(s.getConfidenceVersion())) {
                confidenceV2++;
            }
            if (s.getConfidenceScore() != null) {
                confSum = confSum.add(s.getConfidenceScore());
                confCount++;
            }
            if (s.getProbability() != null) {
                probSum = probSum.add(s.getProbability());
            }
            byOwner.merge(s.getOwnerType() != null ? s.getOwnerType().name() : "UNKNOWN", 1L, Long::sum);
            byLifecycle.merge(s.getLifecycleStatus() != null ? s.getLifecycleStatus() : "UNKNOWN", 1L, Long::sum);
            byOutcome.merge(s.getOutcomeStatus() != null ? s.getOutcomeStatus() : "UNKNOWN", 1L, Long::sum);
        }

        List<AdminStrategyDiagnosticsDto.SignalConfidenceRow> rows = new ArrayList<>();
        for (StrategySignalEntity s : recent.stream().limit(20).toList()) {
            rows.add(new AdminStrategyDiagnosticsDto.SignalConfidenceRow(
                    s.getId().toString(),
                    s.getStrategyName(),
                    s.getSymbol(),
                    s.getOwnerType() != null ? s.getOwnerType().name() : null,
                    s.getLifecycleStatus(),
                    s.getConfidenceScore(),
                    s.getProbability(),
                    s.getTradeQuality(),
                    s.getConfidenceVersion(),
                    s.getConfidenceBreakdownJson() != null && !s.getConfidenceBreakdownJson().isBlank(),
                    s.getEntryPrice(),
                    s.getTargetPrice(),
                    s.getStopPrice(),
                    unifiedAiScore(s.getConfidenceScore())
            ));
        }

        BigDecimal avgConf = confCount == 0 ? null
                : confSum.divide(BigDecimal.valueOf(confCount), 6, RoundingMode.HALF_UP);
        BigDecimal avgProb = confCount == 0 ? null
                : probSum.divide(BigDecimal.valueOf(confCount), 6, RoundingMode.HALF_UP);

        return new AdminStrategyDiagnosticsDto(
                from.toString(),
                to.toString(),
                recent.size(),
                confidenceNull,
                confidenceV2,
                byOwner,
                byLifecycle,
                byOutcome,
                avgConf,
                avgProb,
                rows
        );
    }

    private static double unifiedAiScore(BigDecimal confidence) {
        if (confidence == null) {
            return 0;
        }
        if (confidence.compareTo(BigDecimal.ONE) <= 0) {
            return confidence.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).doubleValue();
        }
        return confidence.setScale(0, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean isPrematureVolumeVacuum(StrategyExitTelemetry e) {
        if (e.getExitReason() == null || !e.getExitReason().toUpperCase().contains("VOLUME_VACUUM")) {
            return false;
        }
        if ("HARD_STOP".equalsIgnoreCase(e.getPressureTrigger()) || "FEED_PROTECTION".equalsIgnoreCase(e.getPressureTrigger())) {
            return false;
        }
        return e.getHoldSeconds() < 180;
    }
}
