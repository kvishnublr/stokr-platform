package com.stokr.engine;

import com.stokr.chartink.ChartinkPosition;
import com.stokr.chartink.ChartinkPositionRepository;
import com.stokr.config.SecurityUtils;
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
    private final ChartinkPositionRepository chartinkPositionRepository;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @GetMapping
    public ResponseEntity<List<SignalEntity>> getMySignals() {
        Long userId = null;
        try {
            userId = SecurityUtils.currentUserId();
        } catch (Exception e) {
            // User not authenticated, fetch all signals
        }
        List<SignalEntity> signals;
        if (userId == null) {
            // Unauthenticated: return all signals
            signals = signalRepository.findTop50ByOrderByCreatedAtDesc();
        } else {
            // Authenticated: return user's signals or public signals
            signals = signalRepository.findTop50ByUserIdOrUserIdIsNullOrderByCreatedAtDesc(userId);
        }
        return ResponseEntity.ok(signals);
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

        // Group closed signals by IST date, compute estimated PnL per trade
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

        // Build cumulative equity curve
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
