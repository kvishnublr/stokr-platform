package com.stokr.bootstrap.trader;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Setup confirmation score for trader signals (distinct from execution quality or ADV market-watch).
 */
public final class ConfirmationRank {

    public static final int SCORE_A_PLUS = 80;
    public static final int SCORE_A = 68;
    public static final int SCORE_WATCH = 50;
    public static final double RR_MIN = 1.5;
    public static final int CONFIDENCE_MIN_PCT = 70;

    private ConfirmationRank() {
    }

    public record Result(int score, String tier, boolean highConviction) {
    }

    public static Result rank(BigDecimal confidenceScore, BigDecimal riskReward, Integer advAiScore) {
        Integer confidencePct = normalizeConfidencePct(confidenceScore);
        int score = 42;

        if (confidencePct != null) {
            score = (int) Math.round(confidencePct * 0.45);
        }

        if (riskReward != null && riskReward.compareTo(BigDecimal.ZERO) > 0) {
            double rr = riskReward.min(new BigDecimal("3")).doubleValue();
            score += (int) Math.round((rr / 3.0) * 35);
        } else {
            score -= 8;
        }

        if (advAiScore != null) {
            int adv = Math.max(0, Math.min(100, advAiScore));
            score += (int) Math.round(adv * 0.2);
        }

        if (riskReward != null && riskReward.compareTo(BigDecimal.valueOf(RR_MIN)) < 0) {
            score = Math.min(score, SCORE_A - 1);
        }

        if (confidencePct != null && confidencePct < 50) {
            score = Math.min(score, SCORE_WATCH + 5);
        }

        score = Math.max(0, Math.min(100, score));
        String tier = tierFromScore(score);

        if (riskReward != null
                && riskReward.compareTo(BigDecimal.valueOf(RR_MIN)) < 0
                && ("A_PLUS".equals(tier) || "A".equals(tier))) {
            tier = "WATCH";
        }

        boolean highConviction = "A_PLUS".equals(tier)
                && (confidencePct == null || confidencePct >= CONFIDENCE_MIN_PCT)
                && riskReward != null
                && riskReward.compareTo(BigDecimal.valueOf(RR_MIN)) >= 0;

        return new Result(score, tier, highConviction);
    }

    static Integer normalizeConfidencePct(BigDecimal value) {
        if (value == null) {
            return null;
        }
        double n = value.doubleValue();
        if (n > 0 && n <= 1) {
            return (int) Math.round(n * 100);
        }
        return (int) Math.round(Math.min(100, Math.max(0, n)));
    }

    static String tierFromScore(int score) {
        if (score >= SCORE_A_PLUS) {
            return "A_PLUS";
        }
        if (score >= SCORE_A) {
            return "A";
        }
        if (score >= SCORE_WATCH) {
            return "WATCH";
        }
        return "SKIP";
    }
}
