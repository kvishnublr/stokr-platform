package com.stokr.execution.sizing;

import com.stokr.execution.capital.StrategyCapitalStateService;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PositionSizingTelemetryService {

    private final PositionSizingTelemetryRepository repository;

    @Transactional
    public void record(PositionSizingRequest request, PositionSizingResult result, UUID orderId) {
        PositionSizingTelemetry row = new PositionSizingTelemetry();
        row.setStrategyName(request.strategyKey());
        row.setSignalId(request.signalId());
        row.setOrderId(orderId);
        row.setSymbol(request.symbol());
        row.setSizingMode(result.sizingMode() != null ? result.sizingMode().name() : null);
        row.setCapitalAllocated(result.snapshot() != null
                ? toBigDecimal(result.snapshot().get("capitalAllocated")) : null);
        row.setCapitalUsed(result.capitalUsed());
        row.setReservedCapital(result.capitalUsed());
        row.setQuantity(result.quantity());
        row.setNormalizedQuantity(result.normalizedQuantity());
        row.setEntryPrice(request.marketPrice());
        row.setExposureValue(result.exposureValue());
        row.setAvailableCapitalBefore(result.availableCapitalBefore());
        row.setAvailableCapitalAfter(result.availableCapitalAfter());
        row.setUtilizationPct(result.utilizationPct());
        row.setRejected(!result.accepted());
        row.setRejectedReason(result.rejectedReason());
        row.setSizingSnapshotHash(result.snapshotHash());
        row.setExecutionMode(request.executionMode() != null ? request.executionMode().name() : null);
        row.setBrokerNormalizationNote(result.brokerNormalizationNote());
        repository.save(row);
        if (!result.accepted()) {
            log.error("sizing.telemetry.REJECTED strategy={} signal={} reason={}",
                    request.strategyKey(), request.signalId(), result.rejectedReason());
        }
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return null;
    }
}
