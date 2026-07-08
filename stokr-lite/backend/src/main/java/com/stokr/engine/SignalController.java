package com.stokr.engine;

import com.stokr.chartink.ChartinkPosition;
import com.stokr.chartink.ChartinkPositionRepository;
import com.stokr.config.SecurityUtils;
import com.stokr.strategy.Strategy;
import com.stokr.strategy.StrategyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class SignalController {

    private final SignalRepository signalRepository;
    private final StrategyRepository strategyRepository;
    private final ChartinkPositionRepository chartinkPositionRepository;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getMySignals() {
        Long userId = null;
        try {
            userId = SecurityUtils.currentUserId();
        } catch (Exception e) {
            // User not authenticated, fetch all signals
        }
        List<SignalEntity> signals;
        if (userId == null) {
            signals = signalRepository.findTop50ByOrderByCreatedAtDesc();
        } else {
            signals = signalRepository.findTop50ByUserIdOrUserIdIsNullOrderByCreatedAtDesc(userId);
        }

        // Build strategy lookup
        Set<Long> strategyIds = signals.stream()
                .map(SignalEntity::getStrategyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Strategy> strategyMap = strategyRepository.findAllById(strategyIds).stream()
                .collect(Collectors.toMap(Strategy::getId, s -> s));

        // Enrich signals with strategy timeframe and name
        List<Map<String, Object>> result = new ArrayList<>();
        for (SignalEntity s : signals) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("deploymentId", s.getDeploymentId());
            row.put("userId", s.getUserId());
            row.put("strategyId", s.getStrategyId());
            row.put("symbol", s.getSymbol());
            row.put("side", s.getSide() != null ? s.getSide().name() : null);
            row.put("entryPrice", s.getEntryPrice());
            row.put("stopLoss", s.getStopLoss());
            row.put("target", s.getTarget());
            row.put("confidence", s.getConfidence());
            row.put("reason", s.getReason());
            row.put("status", s.getStatus());
            row.put("movementScore", s.getMovementScore());
            row.put("source", s.getSource() != null ? s.getSource().name() : null);
            row.put("scannerName", s.getScannerName());
            row.put("metadataJson", s.getMetadataJson());
            row.put("failedFilters", s.getFailedFilters());
            row.put("createdAt", s.getCreatedAt());
            row.put("entryTime", s.getEntryTime());
            row.put("exitTime", s.getExitTime());
            row.put("exitType", s.getExitType());
            row.put("trailTriggerPct", s.getTrailTriggerPct());
            row.put("trailDistancePct", s.getTrailDistancePct());

            // Enriched fields
            if (s.getStrategyId() != null && strategyMap.containsKey(s.getStrategyId())) {
                Strategy strat = strategyMap.get(s.getStrategyId());
                row.put("strategyName", strat.getName());
                row.put("timeframe", strat.getTimeframe());
            } else {
                row.put("strategyName", null);
                row.put("timeframe", null);
            }

            result.add(row);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/active")
    public ResponseEntity<List<SignalEntity>> getActiveSignals() {
        Long userId = SecurityUtils.currentUserId();
        return ResponseEntity.ok(signalRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "GENERATED"));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getSignalStats() {
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();

        long todayCount = signalRepository.countByCreatedAtAfter(todayStart);
        long activeCount = signalRepository.countByStatus("GENERATED");
        long totalCount = signalRepository.countAllBy();

        return ResponseEntity.ok(Map.of(
                "today", todayCount,
                "active", activeCount,
                "total", totalCount
        ));
    }

    @GetMapping("/positions")
    public ResponseEntity<List<ChartinkPosition>> getChartinkPositions() {
        return ResponseEntity.ok(chartinkPositionRepository.findByStatusOrderByCreatedAtDesc("OPEN"));
    }

    @GetMapping("/positions/all")
    public ResponseEntity<List<ChartinkPosition>> getAllChartinkPositions() {
        return ResponseEntity.ok(chartinkPositionRepository.findAll());
    }

    @GetMapping("/pnl-history")
    public ResponseEntity<List<Map<String, Object>>> getPnlHistory(
            @RequestParam(defaultValue = "30") int days) {
        Long userId = SecurityUtils.currentUserId();
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<SignalEntity> signals = signalRepository.findByUserIdAndExitTimeAfter(userId, since);

        Map<LocalDate, Double> byDate = new TreeMap<>();
        for (SignalEntity s : signals) {
            if (s.getExitTime() == null || s.getExitType() == null) continue;
            if (s.getEntryPrice() == null) continue;

            double pnl = 0;
            double entry = s.getEntryPrice().doubleValue();
            if ("TARGET_HIT".equals(s.getExitType()) && s.getTarget() != null) {
                double pct = (s.getTarget().doubleValue() - entry) / entry;
                pnl = pct * 5000;
            } else if ("SL_HIT".equals(s.getExitType()) && s.getStopLoss() != null) {
                double pct = (s.getStopLoss().doubleValue() - entry) / entry;
                pnl = pct * 5000;
            }
            LocalDate date = s.getExitTime().atZone(IST).toLocalDate();
            byDate.merge(date, pnl, Double::sum);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        double cumulative = 0;
        for (Map.Entry<LocalDate, Double> e : byDate.entrySet()) {
            cumulative += e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", e.getKey().toString());
            row.put("daily", Math.round(e.getValue() * 100.0) / 100.0);
            row.put("cumulative", Math.round(cumulative * 100.0) / 100.0);
            result.add(row);
        }
        return ResponseEntity.ok(result);
    }
}
