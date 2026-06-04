package com.stokr.bootstrap.admin;

import com.stokr.common.exception.NotFoundException;
import com.stokr.common.pipeline.OmsIntentDispatcher;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.operational.StrategyExecutionMode;
import com.stokr.strategy.operational.StrategyExecutionModeService;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalOwnerType;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogSignalRegenerateService {

    public static final String ADMIN_REGENERATE_PREFIX = "ADMIN_REGENERATE:";

    private final StrategySignalRepository signalRepository;
    private final StrategySignalPipelineService signalPipelineService;
    private final StrategyExecutionModeService executionModeService;
    private final OmsIntentDispatcher omsIntentDispatcher;
    private final PlatformTransactionManager transactionManager;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    @Value("${stokr.strategy.primary-trader-user-id:}")
    private String primaryTraderUserIdRaw;

    public Map<String, Object> regenerate(UUID sourceSignalId, boolean preferLive) {
        StrategySignalEntity source = resolveSource(sourceSignalId);
        String strategyKey = source.getStrategyName() != null ? source.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;
        String executionMode = resolveExecutionMode(strategyKey, preferLive);
        SignalProvenance provenance = "LIVE".equalsIgnoreCase(executionMode)
                ? SignalProvenance.LIVE
                : SignalProvenance.PAPER;

        StrategySignalEntity clone = cloneForRegenerate(source, provenance);
        StrategySignalEntity saved = signalPipelineService.persistAndDispatch(
                clone,
                "admin-regenerate:" + source.getId(),
                executionMode,
                provenance,
                true);

        if (saved == null) {
            throw new IllegalStateException(
                    "Signal not persisted (session, quality gate, or dedup). strategy="
                            + strategyKey + " symbol=" + source.getSymbol());
        }

        UUID dispatchUserId = resolveDispatchUserId(saved.getUserId(), executionMode);
        SignalPersistedMessage omsMsg = new SignalPersistedMessage(
                saved.getId(),
                dispatchUserId,
                "admin-regenerate-oms:" + saved.getId(),
                null,
                executionMode);
        TransactionTemplate omsTx = new TransactionTemplate(transactionManager);
        omsTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        omsTx.executeWithoutResult(status -> omsIntentDispatcher.dispatch(omsMsg, true));

        log.info("catalog_signal.regenerated sourceId={} newId={} strategy={} symbol={} mode={} dispatchUserId={}",
                source.getId(), saved.getId(), strategyKey, saved.getSymbol(), executionMode, dispatchUserId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceSignalId", source.getId().toString());
        out.put("newSignalId", saved.getId().toString());
        out.put("strategy", strategyKey);
        out.put("symbol", saved.getSymbol());
        out.put("signalType", saved.getSignalType() != null ? saved.getSignalType().name() : null);
        out.put("executionMode", executionMode);
        out.put("dispatchUserId", dispatchUserId.toString());
        out.put("isTestTrade", Boolean.TRUE.equals(saved.getTestTrade()));
        return out;
    }

    private UUID resolveDispatchUserId(UUID signalUserId, String executionMode) {
        if (signalUserId == null || !systemUserId.equals(signalUserId)) {
            return signalUserId != null ? signalUserId : systemUserId;
        }
        if (executionMode == null || !"LIVE".equalsIgnoreCase(executionMode.trim())) {
            return systemUserId;
        }
        if (primaryTraderUserIdRaw == null || primaryTraderUserIdRaw.isBlank()) {
            return systemUserId;
        }
        try {
            return UUID.fromString(primaryTraderUserIdRaw.trim());
        } catch (IllegalArgumentException ex) {
            return systemUserId;
        }
    }

    private StrategySignalEntity resolveSource(UUID sourceSignalId) {
        if (sourceSignalId != null) {
            return signalRepository.findById(sourceSignalId)
                    .filter(s -> !s.isDeleted() && !Boolean.TRUE.equals(s.getTestTrade()))
                    .orElseThrow(() -> new NotFoundException("Production signal not found: " + sourceSignalId));
        }
        List<StrategySignalEntity> latest = signalRepository.findLatestProductionSignals(PageRequest.of(0, 1));
        if (latest.isEmpty()) {
            throw new NotFoundException("No production catalog signal found to regenerate");
        }
        return latest.get(0);
    }

    private String resolveExecutionMode(String strategyKey, boolean preferLive) {
        if (preferLive) {
            return StrategyExecutionMode.LIVE.name();
        }
        return executionModeService.modeFor(strategyKey).name();
    }

    private StrategySignalEntity cloneForRegenerate(StrategySignalEntity source, SignalProvenance provenance) {
        StrategySignalEntity clone = new StrategySignalEntity();
        clone.setSignalType(source.getSignalType() != null ? source.getSignalType() : SignalType.BUY);
        clone.setStrategyName(source.getStrategyName());
        clone.setStrategyVersion(source.getStrategyVersion() != null ? source.getStrategyVersion() : "2.0.0");
        clone.setSymbol(source.getSymbol());
        clone.setSuggestedQty(source.getSuggestedQty());
        clone.setConfidenceScore(source.getConfidenceScore());
        clone.setProbability(source.getProbability());
        clone.setTradeQuality(source.getTradeQuality());
        clone.setConfidenceVersion(source.getConfidenceVersion());
        clone.setConfidenceBreakdownJson(source.getConfidenceBreakdownJson());
        clone.setRsiValue(source.getRsiValue());
        clone.setVwapDistance(source.getVwapDistance());
        clone.setAtrValue(source.getAtrValue());
        clone.setRangeHigh(source.getRangeHigh());
        clone.setRangeLow(source.getRangeLow());
        clone.setMarketRegime(source.getMarketRegime());
        clone.setRejectionPattern(source.getRejectionPattern());
        clone.setReasonText(source.getReasonText());
        clone.setStopPrice(source.getStopPrice());
        clone.setTargetPrice(source.getTargetPrice());
        clone.setEntryReferencePrice(source.getEntryReferencePrice());
        clone.setParameterSnapshotJson(source.getParameterSnapshotJson());
        clone.setIndicatorSnapshotJson(source.getIndicatorSnapshotJson());
        clone.setUserId(source.getUserId() != null ? source.getUserId() : systemUserId);
        clone.setPipeline(provenance.name());
        clone.setSignalSource(provenance);
        clone.setOwnerType(SignalOwnerType.SYSTEM);
        clone.setCandleTimestamp(Instant.now());
        clone.setTestTrade(false);
        clone.setTestRunId(null);
        clone.setBacktestRunId(null);
        clone.setReason(ADMIN_REGENERATE_PREFIX + source.getId());
        clone.setOutcomeStatus(null);
        clone.setOutcomeTime(null);
        clone.setEntryPrice(null);
        clone.setExitPrice(null);
        clone.setRealizedPnl(null);
        clone.setUnrealizedPnl(null);
        return clone;
    }
}
