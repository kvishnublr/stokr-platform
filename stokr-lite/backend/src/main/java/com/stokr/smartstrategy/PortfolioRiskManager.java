package com.stokr.smartstrategy;

import com.stokr.arbitrage.LivePosition;
import com.stokr.arbitrage.LivePositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioRiskManager {

    private final LivePositionRepository positionRepo;

    private static final int MAX_CONCURRENT_POSITIONS = 3;
    private static final double MAX_DAILY_LOSS = 10000.0;
    private static final int MAX_PER_UNDERLYING = 2;
    private static final int MAX_SAME_STRATEGY = 1;

    // Correlated underlying groups — ~85% correlation within each group
    private static final List<Set<String>> CORRELATION_GROUPS = List.of(
        Set.of("NIFTY", "BANKNIFTY", "FINNIFTY")
    );

    public record RiskCheck(boolean allowed, String reason) {}

    public RiskCheck canEnterTrade(Map<String, Object> opportunity) {
        String strategyType = (String) opportunity.getOrDefault("strategyType", "");
        String underlying = (String) opportunity.getOrDefault("underlying", "");

        List<LivePosition> openPositions = getSmartOpenPositions();

        // 1. Max concurrent positions
        if (openPositions.size() >= MAX_CONCURRENT_POSITIONS) {
            return new RiskCheck(false, "Max " + MAX_CONCURRENT_POSITIONS + " concurrent positions reached (" + openPositions.size() + " open)");
        }

        // 2. Daily loss cap
        double todayPnl = getTodayRealizedPnl();
        if (todayPnl < -MAX_DAILY_LOSS) {
            return new RiskCheck(false, String.format("Daily loss cap hit: ₹%.0f (limit ₹%.0f)", todayPnl, MAX_DAILY_LOSS));
        }

        // 3. Max per underlying
        long underlyingCount = openPositions.stream()
            .filter(p -> underlying.equals(p.getUnderlying()))
            .count();
        if (underlyingCount >= MAX_PER_UNDERLYING) {
            return new RiskCheck(false, "Max " + MAX_PER_UNDERLYING + " positions per underlying reached for " + underlying);
        }

        // 4. No duplicate strategy type
        long sameStratCount = openPositions.stream()
            .filter(p -> strategyType.equals(p.getStrategyType()))
            .count();
        if (sameStratCount >= MAX_SAME_STRATEGY) {
            return new RiskCheck(false, "Already have " + sameStratCount + " open " + strategyType + " position(s)");
        }

        // 5. Direction stacking check — don't stack 3+ same-direction bets
        String newDirection = inferDirection(opportunity);
        if (newDirection != null) {
            long sameDir = openPositions.stream()
                .filter(p -> newDirection.equals(inferPositionDirection(p)))
                .count();
            if (sameDir >= 2) {
                return new RiskCheck(false, "Already 2 " + newDirection + " positions open — avoid direction stacking");
            }
        }

        // 6. Correlation check — block same-direction if correlated underlying already open
        if (newDirection != null && !"NEUTRAL".equals(newDirection)) {
            Set<String> correlatedGroup = getCorrelationGroup(underlying);
            if (correlatedGroup != null) {
                for (LivePosition p : openPositions) {
                    if (correlatedGroup.contains(p.getUnderlying()) && !underlying.equals(p.getUnderlying())) {
                        String posDir = inferPositionDirection(p);
                        if (newDirection.equals(posDir)) {
                            return new RiskCheck(false, "Correlated position: " + p.getUnderlying()
                                + " already has " + posDir + " position (correlation risk with " + underlying + ")");
                        }
                    }
                }
            }
        }

        // 7. Max risk exposure check
        double totalOpenRisk = openPositions.stream()
            .mapToDouble(p -> p.getMaxLossAmount() != null ? p.getMaxLossAmount() : 0)
            .sum();
        double newRisk = opportunity.get("maxLoss") instanceof Number n ? n.doubleValue() : 0;
        if (newRisk <= 0) {
            newRisk = opportunity.get("maxLossDown") instanceof Number n ? n.doubleValue() : 0;
        }
        double maxTotalRisk = MAX_DAILY_LOSS * 3;
        if (totalOpenRisk + newRisk > maxTotalRisk) {
            return new RiskCheck(false, String.format("Total risk ₹%.0f + ₹%.0f exceeds limit ₹%.0f",
                totalOpenRisk, newRisk, maxTotalRisk));
        }

        return new RiskCheck(true, "All risk checks passed");
    }

    public Map<String, Object> getPortfolioSummary() {
        List<LivePosition> open = getSmartOpenPositions();
        double todayPnl = getTodayRealizedPnl();
        double openRisk = open.stream()
            .mapToDouble(p -> p.getMaxLossAmount() != null ? p.getMaxLossAmount() : 0)
            .sum();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("openPositions", open.size());
        summary.put("maxPositions", MAX_CONCURRENT_POSITIONS);
        summary.put("todayRealizedPnl", Math.round(todayPnl));
        summary.put("dailyLossLimit", MAX_DAILY_LOSS);
        summary.put("totalOpenRisk", Math.round(openRisk));
        summary.put("canTrade", open.size() < MAX_CONCURRENT_POSITIONS && todayPnl > -MAX_DAILY_LOSS);

        Map<String, Long> byUnderlying = new LinkedHashMap<>();
        Map<String, Long> byStrategy = new LinkedHashMap<>();
        for (LivePosition p : open) {
            byUnderlying.merge(p.getUnderlying(), 1L, Long::sum);
            byStrategy.merge(p.getStrategyType(), 1L, Long::sum);
        }
        summary.put("byUnderlying", byUnderlying);
        summary.put("byStrategy", byStrategy);
        return summary;
    }

    private List<LivePosition> getSmartOpenPositions() {
        List<String> smartTypes = List.of(
            "BROKEN_WING_BUTTERFLY", "RATIO_BUTTERFLY", "SKEW_HARVEST",
            "EXPIRY_THETA_CRUSH", "BOX_SPREAD_ARB", "JADE_LIZARD",
            "CALENDAR_SPREAD_EDGE", "IRON_CONDOR"
        );
        return positionRepo.findAllOpen().stream()
            .filter(p -> "OPEN".equals(p.getStatus()) && smartTypes.contains(p.getStrategyType()))
            .toList();
    }

    private double getTodayRealizedPnl() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return positionRepo.findAll().stream()
            .filter(p -> "CLOSED".equals(p.getStatus()))
            .filter(p -> p.getExitedAt() != null && p.getExitedAt().isAfter(startOfDay))
            .mapToDouble(p -> p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0)
            .sum();
    }

    @SuppressWarnings("unchecked")
    private String inferDirection(Map<String, Object> opp) {
        List<Map<String, Object>> legs = (List<Map<String, Object>>) opp.get("legList");
        if (legs == null) return null;
        int sellPuts = 0, sellCalls = 0;
        for (Map<String, Object> leg : legs) {
            if ("SELL".equals(leg.get("side"))) {
                if ("PE".equals(leg.get("optionType"))) sellPuts++;
                else sellCalls++;
            }
        }
        if (sellPuts > sellCalls) return "BULLISH";
        if (sellCalls > sellPuts) return "BEARISH";
        return "NEUTRAL";
    }

    private Set<String> getCorrelationGroup(String underlying) {
        for (Set<String> group : CORRELATION_GROUPS) {
            if (group.contains(underlying)) return group;
        }
        return null;
    }

    private String inferPositionDirection(LivePosition pos) {
        var legs = pos.getLegs();
        if (legs == null) return "NEUTRAL";
        int sellPuts = 0, sellCalls = 0;
        for (var leg : legs) {
            if ("SELL".equals(leg.get("side"))) {
                if ("PE".equals(leg.get("optionType"))) sellPuts++;
                else sellCalls++;
            }
        }
        if (sellPuts > sellCalls) return "BULLISH";
        if (sellCalls > sellPuts) return "BEARISH";
        return "NEUTRAL";
    }
}
