package com.stokr.chartink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.engine.SignalEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Maps Chartink webhook payloads to SignalEntity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartinkSignalMapper {

    private final ObjectMapper objectMapper;

    public SignalEntity toSignalEntity(ChartinkPayload p, Long userId, Long deploymentId, Long strategyId) {
        String side = p.inferSide();
        String metadata;
        try {
            metadata = objectMapper.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize Chartink payload metadata", e);
            metadata = "{}";
        }

        return SignalEntity.builder()
                .deploymentId(deploymentId)
                .userId(userId)
                .strategyId(strategyId)
                .symbol(p.symbol())
                .side(SignalEntity.Side.valueOf(side))
                .entryPrice(p.ltp())
                .stopLoss(calculateStopLoss(p.ltp(), p.atr14()))
                .target(calculateTarget(p.ltp(), p.atr14()))
                .confidence(p.rvol()) // Use RVOL as confidence proxy
                .reason(p.scannerName() + ": " + p.scanName())
                .status("GENERATED")
                .source(SignalEntity.SignalSource.CHARTINK)
                .scannerName(p.scannerName())
                .metadataJson(metadata)
                .build();
    }

    private BigDecimal calculateStopLoss(BigDecimal ltp, BigDecimal atr) {
        if (ltp == null || atr == null) return null;
        return ltp.subtract(atr.multiply(BigDecimal.valueOf(1.2)));
    }

    private BigDecimal calculateTarget(BigDecimal ltp, BigDecimal atr) {
        if (ltp == null || atr == null) return null;
        return ltp.add(atr.multiply(BigDecimal.valueOf(2.0)));
    }
}
