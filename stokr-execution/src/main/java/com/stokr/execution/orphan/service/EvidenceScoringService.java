package com.stokr.execution.orphan.service;

import com.stokr.execution.orphan.domain.DetectedOrphanPosition;
import com.stokr.execution.orphan.dto.EvidenceScore;
import com.stokr.strategy.domain.StrategySignalEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class EvidenceScoringService {

    public EvidenceScore calculateScore(DetectedOrphanPosition orphan, StrategySignalEntity signal) {
        EvidenceScore score = new EvidenceScore();
        score.setOrphanId(orphan.getId());

        // Placeholder: Score calculation based on:
        // - Signal alignment (time window)
        // - Quantity match
        // - Side match
        // - Price alignment
        // - Conflicting signals

        int totalScore = 0;
        Map<String, Integer> breakdown = new HashMap<>();

        breakdown.put("signal_alignment", 0);
        breakdown.put("quantity_match", 0);
        breakdown.put("side_match", 0);
        breakdown.put("price_alignment", 0);
        breakdown.put("conflict_check", 0);

        score.setEvidenceBreakdown(breakdown);
        score.setScore(totalScore);
        score.setSuportingEvidence("Evidence scoring placeholder - enhanced scoring TBD");

        return score;
    }

    public EvidenceScore calculateScoreNoSignal(DetectedOrphanPosition orphan) {
        EvidenceScore score = new EvidenceScore();
        score.setOrphanId(orphan.getId());
        score.setScore(0);
        score.setSuportingEvidence("No matching signal found");
        return score;
    }
}
