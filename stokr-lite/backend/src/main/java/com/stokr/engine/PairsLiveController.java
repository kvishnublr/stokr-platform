package com.stokr.engine;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/pairs/live")
@RequiredArgsConstructor
public class PairsLiveController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PairTradeRepository pairTradeRepository;
    private final PairsLiveService pairsLiveService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        LocalDate today = LocalDate.now(IST);
        List<PairTrade> todayTrades = pairTradeRepository
            .findByEntryTimeBetweenOrderByEntryTimeDesc(today.atStartOfDay(), today.atTime(23, 59));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mode", pairsLiveService.getMode());
        resp.put("today", todayTrades.stream().map(this::tradeToMap).collect(Collectors.toList()));
        resp.put("openCount", todayTrades.stream().filter(t -> "OPEN".equals(t.getStatus())).count());
        resp.put("closedCount", todayTrades.stream().filter(t -> "CLOSED".equals(t.getStatus())).count());
        double todayPnl = todayTrades.stream()
            .filter(t -> "CLOSED".equals(t.getStatus()) && t.getNetPnl() != null)
            .mapToDouble(PairTrade::getNetPnl).sum();
        resp.put("todayPnl", Math.round(todayPnl * 100.0) / 100.0);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "30") int days) {
        LocalDateTime from = LocalDateTime.now(IST).minusDays(days);
        List<PairTrade> trades = pairTradeRepository.findRecentTrades(from);
        List<PairTrade> closed = trades.stream()
            .filter(t -> "CLOSED".equals(t.getStatus())).collect(Collectors.toList());

        double totalPnl = closed.stream().filter(t -> t.getNetPnl() != null)
            .mapToDouble(PairTrade::getNetPnl).sum();
        long wins = closed.stream().filter(t -> t.getNetPnl() != null && t.getNetPnl() > 0).count();
        double winRate = closed.isEmpty() ? 0 : (wins * 100.0 / closed.size());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mode", pairsLiveService.getMode());
        resp.put("totalTrades", closed.size());
        resp.put("wins", wins);
        resp.put("winRate", Math.round(winRate * 10.0) / 10.0);
        resp.put("totalPnl", Math.round(totalPnl * 100.0) / 100.0);
        resp.put("avgPnlPerTrade", closed.isEmpty() ? 0 : Math.round(totalPnl / closed.size() * 100.0) / 100.0);
        resp.put("trades", trades.stream().map(this::tradeToMap).collect(Collectors.toList()));
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/mode")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> setMode(@RequestParam String mode) {
        if (!mode.equals("PAPER") && !mode.equals("LIVE")) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode must be PAPER or LIVE"));
        }
        pairsLiveService.setMode(mode);
        return ResponseEntity.ok(Map.of("mode", pairsLiveService.getMode(), "status", "ok"));
    }

    @GetMapping("/pairs")
    public ResponseEntity<List<Map<String, String>>> getConfiguredPairs() {
        List<Map<String, String>> pairs = PairsLiveService.LIVE_PAIRS.stream()
            .map(p -> Map.of("symbolA", p[0], "symbolB", p[1], "key", p[0] + ":" + p[1]))
            .collect(Collectors.toList());
        return ResponseEntity.ok(pairs);
    }

    private Map<String, Object> tradeToMap(PairTrade t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("pairKey", t.getPairKey());
        m.put("direction", t.getDirection());
        m.put("status", t.getStatus());
        m.put("mode", t.getMode());
        m.put("entryTime", t.getEntryTime());
        m.put("exitTime", t.getExitTime());
        m.put("entryZ", t.getEntryZ() != null ? Math.round(t.getEntryZ() * 100.0) / 100.0 : null);
        m.put("exitZ", t.getExitZ() != null ? Math.round(t.getExitZ() * 100.0) / 100.0 : null);
        m.put("entryPriceA", t.getEntryPriceA());
        m.put("entryPriceB", t.getEntryPriceB());
        m.put("exitPriceA", t.getExitPriceA());
        m.put("exitPriceB", t.getExitPriceB());
        m.put("qtyA", t.getQtyA());
        m.put("qtyB", t.getQtyB());
        m.put("legAPnl", t.getLegAPnl() != null ? Math.round(t.getLegAPnl() * 100.0) / 100.0 : null);
        m.put("legBPnl", t.getLegBPnl() != null ? Math.round(t.getLegBPnl() * 100.0) / 100.0 : null);
        m.put("netPnl", t.getNetPnl() != null ? Math.round(t.getNetPnl() * 100.0) / 100.0 : null);
        m.put("exitReason", t.getExitReason());
        return m;
    }
}
