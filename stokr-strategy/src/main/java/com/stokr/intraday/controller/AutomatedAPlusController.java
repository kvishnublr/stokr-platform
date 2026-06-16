package com.stokr.intraday.controller;

import com.stokr.intraday.domain.AutomatedAPlusTrade;
import com.stokr.intraday.repository.AutomatedAPlusTradeRepository;
import com.stokr.intraday.repository.APlusStrategyConfigRepository;
import com.stokr.intraday.domain.APlusStrategyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/v1/automated-aplus")
@RequiredArgsConstructor
public class AutomatedAPlusController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AutomatedAPlusTradeRepository tradeRepository;
    private final APlusStrategyConfigRepository configRepository;

    @GetMapping("/active-trades")
    public ResponseEntity<List<AutomatedAPlusTrade>> getActiveTrades() {
        List<AutomatedAPlusTrade> trades = tradeRepository.findByStatusOrderByEntryTimeDesc("ACTIVE");
        return ResponseEntity.ok(trades);
    }

    @GetMapping("/trades/today")
    public ResponseEntity<Map<String, Object>> getTodaysTrades() {
        LocalDate today = LocalDate.now(IST);
        Instant dayStart = today.atStartOfDay(IST).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();

        List<AutomatedAPlusTrade> trades = tradeRepository.findByEntryTimeRange(dayStart, dayEnd);

        long active = trades.stream().filter(t -> "ACTIVE".equals(t.getStatus())).count();
        long exited = trades.stream().filter(t -> "EXITED".equals(t.getStatus())).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalTrades", trades.size());
        summary.put("activeTrades", active);
        summary.put("exitedTrades", exited);
        summary.put("trades", trades);

        return ResponseEntity.ok(summary);
    }

    @GetMapping("/trades/symbol/{symbol}")
    public ResponseEntity<List<AutomatedAPlusTrade>> getTradesBySymbol(@PathVariable String symbol) {
        List<AutomatedAPlusTrade> trades = tradeRepository.findBySymbolOrderByEntryTimeDesc(symbol);
        return ResponseEntity.ok(trades);
    }

    @GetMapping("/config")
    public ResponseEntity<APlusStrategyConfig> getConfig() {
        Optional<APlusStrategyConfig> config = configRepository.findById(1L);
        return config.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/config")
    public ResponseEntity<APlusStrategyConfig> updateConfig(@RequestBody APlusStrategyConfig config) {
        config.setId(1L);
        APlusStrategyConfig saved = configRepository.save(config);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/config/toggle")
    public ResponseEntity<APlusStrategyConfig> toggleStrategy() {
        Optional<APlusStrategyConfig> config = configRepository.findById(1L);
        if (config.isPresent()) {
            APlusStrategyConfig cfg = config.get();
            cfg.setEnabled(!cfg.getEnabled());
            APlusStrategyConfig saved = configRepository.save(cfg);
            return ResponseEntity.ok(saved);
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long activeCount = tradeRepository.countActivePositions();
        List<AutomatedAPlusTrade> exited = tradeRepository.findByStatusOrderByEntryTimeDesc("EXITED");

        double avgPnL = exited.stream()
                .filter(t -> t.getPnl() != null)
                .mapToDouble(t -> t.getPnl().doubleValue())
                .average()
                .orElse(0.0);

        long winners = exited.stream()
                .filter(t -> t.getPnl() != null && t.getPnl().signum() > 0)
                .count();

        long losers = exited.stream()
                .filter(t -> t.getPnl() != null && t.getPnl().signum() < 0)
                .count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activePositions", activeCount);
        stats.put("totalTradesExited", exited.size());
        stats.put("winners", winners);
        stats.put("losers", losers);
        stats.put("winRate", exited.size() > 0 ? (double) winners / exited.size() * 100 : 0);
        stats.put("avgPnL", avgPnL);

        return ResponseEntity.ok(stats);
    }
}
