package com.stokr.strategy.service;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.signals.SignalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Drops low-quality production signals before persistence (confidence + risk/reward floors).
 */
@Service
@Slf4j
public class SignalQualityGateService {

    @Value("${stokr.strategy.quality-gate.enabled:true}")
    private boolean enabled;

    @Value("${stokr.strategy.quality-gate.min-confidence:0.55}")
    private double minConfidence;

    @Value("${stokr.strategy.quality-gate.min-risk-reward:1.2}")
    private double minRiskReward;

    public boolean shouldDrop(StrategySignalEntity signal) {
        return dropReason(signal) != null;
    }

    /** Returns human-readable rejection reason, or null if signal passes. */
    public String dropReason(StrategySignalEntity signal) {
        if (!enabled || signal == null || Boolean.TRUE.equals(signal.getTestTrade())) {
            return null;
        }
        if (signal.getSignalType() == SignalType.EXIT) {
            return null;
        }
        if (signal.getBacktestRunId() != null) {
            return null;
        }
        if (signal.getSignalSource() != null && !signal.getSignalSource().isProductionAnalytics()) {
            return null;
        }
        BigDecimal confidence = signal.getConfidenceScore();
        if (confidence == null) {
            return "Confidence missing";
        }
        if (confidence != null && confidence.doubleValue() < minConfidence) {
            log.info("signal.dropped_low_confidence strategy={} symbol={} confidence={} min={}",
                    signal.getStrategyName(), signal.getSymbol(), confidence, minConfidence);
            return "Confidence " + confidence + " below minimum " + minConfidence;
        }
        BigDecimal rr = estimateRiskReward(signal);
        if (rr != null && rr.doubleValue() < minRiskReward) {
            log.info("signal.dropped_low_rr strategy={} symbol={} rr={} min={}",
                    signal.getStrategyName(), signal.getSymbol(), rr, minRiskReward);
            return "Risk/reward " + rr + " below minimum " + minRiskReward;
        }
        return null;
    }

    private BigDecimal estimateRiskReward(StrategySignalEntity signal) {
        BigDecimal entry = signal.getEntryReferencePrice();
        BigDecimal target = signal.getTargetPrice();
        BigDecimal sl = signal.getStopPrice();
        if (entry == null || target == null || sl == null) {
            return null;
        }
        if (entry.signum() <= 0) {
            return null;
        }
        BigDecimal risk = entry.subtract(sl).abs();
        if (risk.signum() <= 0) {
            return null;
        }
        BigDecimal reward = target.subtract(entry).abs();
        if (signal.getSignalType() == SignalType.SELL) {
            reward = entry.subtract(target).abs();
        }
        if (reward.signum() <= 0) {
            return null;
        }
        return reward.divide(risk, 4, RoundingMode.HALF_UP);
    }
}
