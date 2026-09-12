package com.stokr.smartstrategy;

import com.stokr.arbitrage.LivePosition;
import com.stokr.arbitrage.LivePositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradePerformanceService {

    private final LivePositionRepository positionRepo;

    private static final List<String> SMART_TYPES = List.of(
        "BROKEN_WING_BUTTERFLY", "RATIO_BUTTERFLY", "SKEW_HARVEST",
        "EXPIRY_THETA_CRUSH", "BOX_SPREAD_ARB", "JADE_LIZARD",
        "CALENDAR_SPREAD_EDGE", "IRON_CONDOR"
    );

    public Map<String, Object> getPerformanceReport() {
        List<LivePosition> allClosed = positionRepo.findAll().stream()
            .filter(p -> "CLOSED".equals(p.getStatus()) || "EXITED".equals(p.getStatus()))
            .filter(p -> SMART_TYPES.contains(p.getStrategyType()))
            .sorted(Comparator.comparing(p -> p.getExitedAt() != null ? p.getExitedAt() : p.getCreatedAt(),
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalTrades", allClosed.size());

        if (allClosed.isEmpty()) {
            report.put("winRate", 0);
            report.put("totalPnl", 0);
            report.put("avgWin", 0);
            report.put("avgLoss", 0);
            report.put("bestTrade", 0);
            report.put("worstTrade", 0);
            report.put("profitFactor", 0);
            report.put("byStrategy", Map.of());
            report.put("byExitReason", Map.of());
            report.put("recentTrades", List.of());
            report.put("streaks", Map.of("currentWin", 0, "currentLoss", 0, "maxWin", 0, "maxLoss", 0));
            return report;
        }

        List<Double> pnls = allClosed.stream()
            .map(p -> p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0.0)
            .toList();

        long wins = pnls.stream().filter(p -> p > 0).count();
        double totalPnl = pnls.stream().mapToDouble(d -> d).sum();
        double avgWin = pnls.stream().filter(p -> p > 0).mapToDouble(d -> d).average().orElse(0);
        double avgLoss = pnls.stream().filter(p -> p <= 0).mapToDouble(d -> d).average().orElse(0);
        double bestTrade = pnls.stream().mapToDouble(d -> d).max().orElse(0);
        double worstTrade = pnls.stream().mapToDouble(d -> d).min().orElse(0);
        double grossWins = pnls.stream().filter(p -> p > 0).mapToDouble(d -> d).sum();
        double grossLosses = Math.abs(pnls.stream().filter(p -> p <= 0).mapToDouble(d -> d).sum());

        report.put("winRate", allClosed.size() > 0 ? Math.round(wins * 100.0 / allClosed.size()) : 0);
        report.put("totalPnl", Math.round(totalPnl));
        report.put("avgWin", Math.round(avgWin));
        report.put("avgLoss", Math.round(avgLoss));
        report.put("bestTrade", Math.round(bestTrade));
        report.put("worstTrade", Math.round(worstTrade));
        report.put("profitFactor", grossLosses > 0 ? Math.round(grossWins / grossLosses * 100.0) / 100.0 : grossWins > 0 ? 999.0 : 0);

        // Per-strategy breakdown
        Map<String, Map<String, Object>> byStrategy = new LinkedHashMap<>();
        Map<String, List<LivePosition>> grouped = allClosed.stream()
            .collect(Collectors.groupingBy(p -> p.getStrategyType() != null ? p.getStrategyType() : "UNKNOWN"));
        for (var entry : grouped.entrySet()) {
            byStrategy.put(entry.getKey(), computeStrategyStats(entry.getValue()));
        }
        report.put("byStrategy", byStrategy);

        // By exit reason
        Map<String, Long> byExitReason = allClosed.stream()
            .collect(Collectors.groupingBy(
                p -> p.getExitReason() != null ? p.getExitReason() : "UNKNOWN",
                Collectors.counting()
            ));
        report.put("byExitReason", byExitReason);

        // Recent trades (last 20)
        List<Map<String, Object>> recentTrades = allClosed.stream()
            .limit(20)
            .map(this::toTradeEntry)
            .toList();
        report.put("recentTrades", recentTrades);

        // Streaks
        report.put("streaks", computeStreaks(pnls));

        // Today's stats
        report.put("todayStats", computeDayStats(allClosed, LocalDate.now(ZoneId.of("Asia/Kolkata"))));

        return report;
    }

    public Map<String, Object> getDailyPerformance(int days) {
        LocalDateTime cutoff = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(days).atStartOfDay();
        List<LivePosition> recent = positionRepo.findAll().stream()
            .filter(p -> "CLOSED".equals(p.getStatus()) || "EXITED".equals(p.getStatus()))
            .filter(p -> SMART_TYPES.contains(p.getStrategyType()))
            .filter(p -> p.getExitedAt() != null && p.getExitedAt().isAfter(cutoff))
            .toList();

        Map<LocalDate, List<LivePosition>> byDay = recent.stream()
            .collect(Collectors.groupingBy(p -> p.getExitedAt().toLocalDate()));

        List<Map<String, Object>> dailyData = new ArrayList<>();
        double cumPnl = 0;
        for (int i = days; i >= 0; i--) {
            LocalDate date = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(i);
            List<LivePosition> dayTrades = byDay.getOrDefault(date, List.of());
            double dayPnl = dayTrades.stream()
                .mapToDouble(p -> p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0)
                .sum();
            cumPnl += dayPnl;

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date.toString());
            day.put("trades", dayTrades.size());
            day.put("pnl", Math.round(dayPnl));
            day.put("cumulativePnl", Math.round(cumPnl));
            day.put("wins", dayTrades.stream().filter(p -> p.getCurrentPnl() != null && p.getCurrentPnl().doubleValue() > 0).count());
            dailyData.add(day);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("dailyData", dailyData);
        result.put("totalTrades", recent.size());
        result.put("totalPnl", Math.round(recent.stream()
            .mapToDouble(p -> p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0).sum()));
        return result;
    }

    private Map<String, Object> computeStrategyStats(List<LivePosition> positions) {
        Map<String, Object> stats = new LinkedHashMap<>();
        List<Double> pnls = positions.stream()
            .map(p -> p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0.0)
            .toList();
        long wins = pnls.stream().filter(p -> p > 0).count();
        double totalPnl = pnls.stream().mapToDouble(d -> d).sum();

        stats.put("trades", positions.size());
        stats.put("wins", wins);
        stats.put("losses", positions.size() - wins);
        stats.put("winRate", positions.size() > 0 ? Math.round(wins * 100.0 / positions.size()) : 0);
        stats.put("totalPnl", Math.round(totalPnl));
        stats.put("avgPnl", Math.round(totalPnl / Math.max(positions.size(), 1)));
        stats.put("bestTrade", Math.round(pnls.stream().mapToDouble(d -> d).max().orElse(0)));
        stats.put("worstTrade", Math.round(pnls.stream().mapToDouble(d -> d).min().orElse(0)));
        return stats;
    }

    private Map<String, Object> computeDayStats(List<LivePosition> allClosed, LocalDate date) {
        List<LivePosition> today = allClosed.stream()
            .filter(p -> p.getExitedAt() != null && p.getExitedAt().toLocalDate().equals(date))
            .toList();
        Map<String, Object> stats = new LinkedHashMap<>();
        double pnl = today.stream()
            .mapToDouble(p -> p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0)
            .sum();
        long wins = today.stream()
            .filter(p -> p.getCurrentPnl() != null && p.getCurrentPnl().doubleValue() > 0)
            .count();
        stats.put("trades", today.size());
        stats.put("wins", wins);
        stats.put("pnl", Math.round(pnl));
        stats.put("winRate", today.size() > 0 ? Math.round(wins * 100.0 / today.size()) : 0);
        return stats;
    }

    private Map<String, Object> computeStreaks(List<Double> pnls) {
        int maxWin = 0, maxLoss = 0;
        int tempWin = 0, tempLoss = 0;
        for (double pnl : pnls) {
            if (pnl > 0) {
                tempWin++;
                tempLoss = 0;
            } else {
                tempLoss++;
                tempWin = 0;
            }
            maxWin = Math.max(maxWin, tempWin);
            maxLoss = Math.max(maxLoss, tempLoss);
        }
        int currentWin = 0, currentLoss = 0;
        for (double pnl : pnls) {
            if (pnl > 0) currentWin++;
            else break;
        }
        for (double pnl : pnls) {
            if (pnl <= 0) currentLoss++;
            else break;
        }
        Map<String, Object> streaks = new LinkedHashMap<>();
        streaks.put("currentWin", currentWin);
        streaks.put("currentLoss", currentLoss);
        streaks.put("maxWin", maxWin);
        streaks.put("maxLoss", maxLoss);
        return streaks;
    }

    private Map<String, Object> toTradeEntry(LivePosition pos) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", pos.getId());
        entry.put("strategyType", pos.getStrategyType());
        entry.put("underlying", pos.getUnderlying());
        entry.put("broker", pos.getBroker());
        entry.put("pnl", pos.getCurrentPnl() != null ? pos.getCurrentPnl().doubleValue() : 0);
        entry.put("exitReason", pos.getExitReason());
        entry.put("enteredAt", pos.getEnteredAt() != null ? pos.getEnteredAt().toString() : null);
        entry.put("exitedAt", pos.getExitedAt() != null ? pos.getExitedAt().toString() : null);
        entry.put("lots", pos.getLots());
        entry.put("maxLoss", pos.getMaxLossAmount());
        entry.put("maxProfit", pos.getMaxProfitAmount());
        entry.put("win", pos.getCurrentPnl() != null && pos.getCurrentPnl().doubleValue() > 0);
        return entry;
    }
}
