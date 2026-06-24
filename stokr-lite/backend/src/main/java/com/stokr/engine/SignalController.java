package com.stokr.engine;

import com.stokr.chartink.ChartinkPosition;
import com.stokr.chartink.ChartinkPositionRepository;
import com.stokr.config.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

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
}
