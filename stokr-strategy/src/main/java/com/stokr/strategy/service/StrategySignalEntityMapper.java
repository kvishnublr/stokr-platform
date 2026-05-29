package com.stokr.strategy.service;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.signals.SignalOwnerType;
import com.stokr.strategy.signals.StrategySignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** Maps {@link StrategySignal} into a persistable entity with optional entry/stop/target. */
public final class StrategySignalEntityMapper {

    private StrategySignalEntityMapper() {
    }

    public static void applyPrices(StrategySignalEntity entity, StrategySignal signal) {
        if (entity == null || signal == null) {
            return;
        }
        if (signal.entryPrice() != null) {
            entity.setEntryReferencePrice(scale(signal.entryPrice()));
            entity.setEntryPrice(scale(signal.entryPrice()));
        }
        if (signal.stopPrice() != null) {
            entity.setStopPrice(scale(signal.stopPrice()));
        }
        if (signal.targetPrice() != null) {
            entity.setTargetPrice(scale(signal.targetPrice()));
        }
    }

    public static StrategySignalEntity baseEntity(
            StrategySignal signal,
            String strategyKey,
            String symbol,
            Instant candleTime,
            UUID userId,
            String pipeline,
            String strategyVersion
    ) {
        StrategySignalEntity entity = new StrategySignalEntity();
        entity.setSignalType(signal.type());
        entity.setSymbol(symbol);
        entity.setStrategyName(strategyKey);
        entity.setStrategyVersion(strategyVersion);
        entity.setReasonText(signal.reason());
        entity.setReason(signal.reason());
        entity.setSuggestedQty(signal.suggestedQty() != null ? signal.suggestedQty() : BigDecimal.ONE);
        entity.setConfidenceScore(signal.confidenceScore());
        entity.setProbability(signal.probability());
        entity.setTradeQuality(signal.tradeQuality());
        entity.setConfidenceVersion(signal.confidenceVersion());
        entity.setConfidenceBreakdownJson(signal.confidenceBreakdownJson());
        entity.setCandleTimestamp(candleTime);
        entity.setUserId(userId);
        entity.setPipeline(pipeline);
        entity.setHitTarget(false);
        entity.setHitStoploss(false);
        applyPrices(entity, signal);
        return entity;
    }

    public static void applyStreamMetadata(
            StrategySignalEntity entity,
            SignalOwnerType ownerType,
            String lifecycleStatus
    ) {
        if (entity == null) {
            return;
        }
        if (ownerType != null) {
            entity.setOwnerType(ownerType);
        }
        if (lifecycleStatus != null && !lifecycleStatus.isBlank()) {
            SignalLifecycleService.applyInitial(entity, lifecycleStatus);
        }
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }
}
