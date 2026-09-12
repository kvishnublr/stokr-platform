package com.stokr.smartstrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StrategyScoreEngine {

    private static final Logger log = LoggerFactory.getLogger(StrategyScoreEngine.class);

    public double score(Map<String, Object> opportunity) {
        String type = (String) opportunity.getOrDefault("strategyType", "");

        double edgeScore = scoreEdgeQuality(opportunity);
        double winScore = scoreWinProbability(opportunity);
        double liquidityScore = scoreLiquidity(opportunity);
        double riskScore = scoreRiskReward(opportunity);
        double timeScore = scoreTimeValue(opportunity);

        // Weighted composite — edge and win matter most
        double composite = edgeScore * 0.25 + winScore * 0.25 + liquidityScore * 0.15
            + riskScore * 0.20 + timeScore * 0.15;

        return Math.round(composite * 100.0) / 100.0;
    }

    public List<Map<String, Object>> rankAndFilter(List<Map<String, Object>> opportunities, double minScore) {
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> opp : opportunities) {
            double s = score(opp);
            Map<String, Object> enriched = new LinkedHashMap<>(opp);
            enriched.put("compositeScore", s);
            enriched.put("scoreBreakdown", Map.of(
                "edge", scoreEdgeQuality(opp),
                "win", scoreWinProbability(opp),
                "liquidity", scoreLiquidity(opp),
                "risk", scoreRiskReward(opp),
                "time", scoreTimeValue(opp)
            ));
            if (s >= minScore) {
                scored.add(enriched);
            }
        }
        scored.sort((a, b) -> Double.compare(
            ((Number) b.get("compositeScore")).doubleValue(),
            ((Number) a.get("compositeScore")).doubleValue()));
        return scored;
    }

    private double scoreEdgeQuality(Map<String, Object> opp) {
        double creditRs = getDouble(opp, "creditRs");
        double edgeAfterCosts = getDouble(opp, "edgeAfterCosts");
        int lotSize = getInt(opp, "lotSize", 75);

        if (creditRs <= 0 && edgeAfterCosts <= 0) return 0;

        double edgePerLot = edgeAfterCosts > 0 ? edgeAfterCosts : creditRs;
        // ₹500/lot = decent, ₹2000/lot = excellent
        double score = Math.min(100, edgePerLot / 20.0);

        // Bonus for positive edge after costs
        if (edgeAfterCosts > 0) score = Math.min(100, score * 1.2);

        return Math.max(0, Math.min(100, score));
    }

    private double scoreWinProbability(Map<String, Object> opp) {
        double estimatedWinRate = getDouble(opp, "estimatedWinRate");
        if (estimatedWinRate <= 0) {
            // Fallback: compute from spot distance
            double spot = getDouble(opp, "spotPrice");
            if (spot <= 0) return 30;
            double minStrikeDist = computeMinStrikeDistance(opp, spot);
            double distPct = minStrikeDist / spot * 100;
            estimatedWinRate = Math.min(90, 50 + distPct * 3);
        }
        // 50% win = 20 score, 70% = 60 score, 85% = 90 score
        return Math.max(0, Math.min(100, (estimatedWinRate - 40) * 2));
    }

    @SuppressWarnings("unchecked")
    private double computeMinStrikeDistance(Map<String, Object> opp, double spot) {
        List<Map<String, Object>> legs = (List<Map<String, Object>>) opp.get("legList");
        if (legs == null) return 0;
        double minDist = Double.MAX_VALUE;
        for (Map<String, Object> leg : legs) {
            if ("SELL".equals(leg.get("side"))) {
                int strike = ((Number) leg.get("strike")).intValue();
                minDist = Math.min(minDist, Math.abs(spot - strike));
            }
        }
        return minDist == Double.MAX_VALUE ? 0 : minDist;
    }

    @SuppressWarnings("unchecked")
    private double scoreLiquidity(Map<String, Object> opp) {
        List<Map<String, Object>> legs = (List<Map<String, Object>>) opp.get("legList");
        if (legs == null) return 50;

        // Use bid-ask spread tightness as liquidity proxy
        double totalSpread = 0;
        double totalPrice = 0;
        int count = 0;
        for (Map<String, Object> leg : legs) {
            double price = getDouble(leg, "price");
            if (price > 0) {
                totalPrice += price;
                count++;
            }
        }

        if (count == 0 || totalPrice <= 0) return 40;

        // Check OI if available at opportunity level
        double oi = getDouble(opp, "avgOI");
        if (oi <= 0) {
            // Estimate from strategy type — well-known strikes have better liquidity
            String type = (String) opp.getOrDefault("strategyType", "");
            if (type.contains("BOX") || type.contains("IRON")) return 60;
            return 50;
        }

        // OI scoring: 10k = low, 100k = good, 500k+ = excellent
        return Math.min(100, 30 + Math.log10(Math.max(1, oi)) * 15);
    }

    private double scoreRiskReward(Map<String, Object> opp) {
        double creditRs = getDouble(opp, "creditRs");
        double maxLoss = getDouble(opp, "maxLoss");
        if (maxLoss <= 0) maxLoss = getDouble(opp, "maxLossDown");
        if (maxLoss <= 0) return 50;

        double rr = creditRs / maxLoss;
        // R:R of 0.3 = 30, 0.5 = 50, 1.0 = 100
        return Math.max(0, Math.min(100, rr * 100));
    }

    private double scoreTimeValue(Map<String, Object> opp) {
        long dte = getLong(opp, "dte");
        if (dte <= 0) return 50;

        // Sweet spot: 2-5 DTE for weekly strategies (max theta decay)
        // Too short (<1) = gamma risk, too long (>7) = slow decay
        if (dte == 1) return 60;
        if (dte >= 2 && dte <= 3) return 95;
        if (dte >= 4 && dte <= 5) return 85;
        if (dte >= 6 && dte <= 7) return 65;
        return Math.max(20, 70 - (dte - 5) * 5);
    }

    private double getDouble(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private int getInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private long getLong(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : 0;
    }
}
