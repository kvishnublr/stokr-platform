package com.stokr.execution.orphan.service;

import com.stokr.execution.orphan.domain.DetectedOrphanPosition;
import com.stokr.execution.orphan.dto.EvidenceScore;
import com.stokr.strategy.domain.StrategySignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class EvidenceScoringService {

    public EvidenceScore calculateScore(DetectedOrphanPosition orphan, StrategySignal signal) {
        EvidenceScore score = new EvidenceScore();
        score.setOrphanId(orphan.getId());

        int totalScore = 0;
        Map<String, Integer> breakdown = new HashMap<>();

        // Signal alignment scoring
        int signalScore = scoreSignalAlignment(signal, orphan);
        totalScore += signalScore;
        breakdown.put("signal_alignment", signalScore);

        // Quantity match scoring
        int qtyScore = scoreQuantityMatch(orphan, signal);
        totalScore += qtyScore;
        breakdown.put("quantity_match", qtyScore);

        // Side match scoring
        int sideScore = scoreSideMatch(orphan, signal);
        totalScore += sideScore;
        breakdown.put("side_match", sideScore);

        // Price alignment scoring
        int priceScore = scorePriceAlignment(orphan, signal);
        totalScore += priceScore;
        breakdown.put("price_alignment", priceScore);

        // Conflict check scoring
        int conflictScore = scoreConflictingSignals(orphan);
        totalScore += conflictScore;
        breakdown.put("conflict_check", conflictScore);

        score.setEvidenceBreakdown(breakdown);
        score.setScore(Math.min(100, totalScore));
        score.setSuportingEvidence(extractEvidence(orphan, signal));

        return score;
    }

    public EvidenceScore calculateScoreNoSignal(DetectedOrphanPosition orphan) {
        EvidenceScore score = new EvidenceScore();
        score.setOrphanId(orphan.getId());
        score.setScore(0);
        score.setSuportingEvidence("No matching signal found");
        return score;
    }

    private int scoreSignalAlignment(StrategySignal signal, DetectedOrphanPosition orphan) {
        if (signal == null) return 0;

        Duration gap = Duration.between(signal.getCreatedAt(), orphan.getBrokerEntryTime()).abs();
        if (gap.toMinutes() <= 5) return 25;
        if (gap.toMinutes() <= 10) return 20;
        if (gap.toMinutes() <= 30) return 10;
        return 0;
    }

    private int scoreQuantityMatch(DetectedOrphanPosition orphan, StrategySignal signal) {
        if (signal == null || signal.getQuantity() == null) return 0;

        BigDecimal signalQty = new BigDecimal(signal.getQuantity());
        BigDecimal diff = orphan.getQuantity().subtract(signalQty).abs();
        BigDecimal pct = diff.divide(signalQty, 4, java.math.RoundingMode.HALF_UP)
                            .multiply(new BigDecimal(100));

        if (pct.compareTo(new BigDecimal("0")) == 0) return 25;
        if (pct.compareTo(new BigDecimal("1")) <= 0) return 20;
        if (pct.compareTo(new BigDecimal("5")) <= 0) return 10;
        return 0;
    }

    private int scoreSideMatch(DetectedOrphanPosition orphan, StrategySignal signal) {
        if (signal == null) return 0;
        return "BUY".equalsIgnoreCase(signal.getSide().name()) &&
               "BUY".equalsIgnoreCase(orphan.getQuantity().signum() > 0 ? "BUY" : "SELL") ? 25 : 0;
    }

    private int scorePriceAlignment(DetectedOrphanPosition orphan, StrategySignal signal) {
        if (signal == null || signal.getReferencePrice() == null || orphan.getBrokerEntryPrice() == null) return 0;

        BigDecimal sigPrice = signal.getReferencePrice();
        BigDecimal brokerPrice = orphan.getBrokerEntryPrice();
        BigDecimal diff = sigPrice.subtract(brokerPrice).abs();
        BigDecimal pct = diff.divide(sigPrice, 4, java.math.RoundingMode.HALF_UP)
                           .multiply(new BigDecimal(100));

        if (pct.compareTo(new BigDecimal("1")) <= 0) return 15;
        if (pct.compareTo(new BigDecimal("2")) <= 0) return 10;
        if (pct.compareTo(new BigDecimal("5")) <= 0) return 5;
        return 0;
    }

    private int scoreConflictingSignals(DetectedOrphanPosition orphan) {
        // Placeholder for conflicting signals check
        return 10;
    }

    private String extractEvidence(DetectedOrphanPosition orphan, StrategySignal signal) {
        StringBuilder sb = new StringBuilder();
        if (signal != null) {
            sb.append("Signal found: ").append(signal.getId()).append("\n");
            sb.append("Signal time: ").append(signal.getCreatedAt()).append("\n");
            sb.append("Signal qty: ").append(signal.getQuantity()).append("\n");
        }
        sb.append("Broker qty: ").append(orphan.getQuantity()).append("\n");
        sb.append("Entry time: ").append(orphan.getBrokerEntryTime());
        return sb.toString();
    }
}
