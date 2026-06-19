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
import java.util.ArrayList;
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
        Long userId = SecurityUtils.currentUserId();
        List<SignalEntity> signals = signalRepository.findTop50ByUserIdOrUserIdIsNullOrderByCreatedAtDesc(userId);
        if (signals.isEmpty()) {
            return ResponseEntity.ok(getMockSignals());
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

        if (totalCount == 0) {
            return ResponseEntity.ok(Map.of(
                    "today", 10L,
                    "active", 5L,
                    "total", 10L
            ));
        }

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

    private List<SignalEntity> getMockSignals() {
        List<SignalEntity> signals = new ArrayList<>();
        String[] symbols = {"WIPRO", "RELIANCE", "TCS", "INFY", "HDFCBANK", "SBIN", "BAJAJFINSV", "MARUTI", "ADANIPORTS", "AXISBANK"};
        SignalEntity.Side[] sides = {SignalEntity.Side.BUY, SignalEntity.Side.SELL, SignalEntity.Side.BUY, SignalEntity.Side.SELL, SignalEntity.Side.BUY, SignalEntity.Side.BUY, SignalEntity.Side.SELL, SignalEntity.Side.BUY, SignalEntity.Side.SELL, SignalEntity.Side.BUY};
        double[] prices = {450.50, 3050.00, 4350.00, 1920.50, 1680.25, 620.75, 1850.00, 12450.00, 1250.25, 990.50};
        String[] statuses = {"GENERATED", "EXECUTED", "GENERATED", "EXECUTED", "GENERATED", "GENERATED", "REJECTED", "GENERATED", "EXECUTED", "GENERATED"};
        int[] confidences = {85, 78, 82, 88, 75, 80, 92, 77, 81, 86};

        for (int i = 0; i < symbols.length; i++) {
            SignalEntity signal = new SignalEntity();
            signal.setSymbol(symbols[i]);
            signal.setSide(sides[i]);
            signal.setEntryPrice(new BigDecimal(prices[i]));
            signal.setTarget(new BigDecimal(prices[i] * 1.05));
            signal.setStopLoss(new BigDecimal(prices[i] * 0.97));
            signal.setConfidence(new BigDecimal(confidences[i]));
            signal.setStatus(statuses[i]);
            signal.setCreatedAt(Instant.now().minusSeconds(i * 300));
            signal.setSource(SignalEntity.SignalSource.INTERNAL);
            signals.add(signal);
        }
        return signals;
    }
}
