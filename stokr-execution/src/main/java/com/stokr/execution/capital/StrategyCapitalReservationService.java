package com.stokr.execution.capital;

import com.stokr.execution.sizing.PositionSizingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyCapitalReservationService {

    private final StrategyCapitalReservationRepository repository;

    @Transactional
    public StrategyCapitalReservation reserve(
            String strategyKey,
            UUID userId,
            UUID signalId,
            UUID orderId,
            String symbol,
            PositionSizingResult sizing) {
        StrategyCapitalReservation row = new StrategyCapitalReservation();
        row.setStrategyKey(strategyKey);
        row.setUserId(userId);
        row.setSignalId(signalId);
        row.setOrderId(orderId);
        row.setSymbol(symbol);
        row.setReservedAmount(sizing.capitalUsed() != null ? sizing.capitalUsed() : BigDecimal.ZERO);
        row.setReservedQuantity(sizing.normalizedQuantity() != null ? sizing.normalizedQuantity() : sizing.quantity());
        row.setEntryPrice(sizing.exposureValue() != null && sizing.normalizedQuantity() != null
                && sizing.normalizedQuantity().compareTo(BigDecimal.ZERO) > 0
                ? sizing.exposureValue().divide(sizing.normalizedQuantity(), 8, java.math.RoundingMode.HALF_UP)
                : null);
        row.setStatus("ACTIVE");
        row.setSizingSnapshotHash(sizing.snapshotHash());
        row.setDeleted(false);
        row.setVersion(0);
        repository.save(row);
        log.info("capital.reserved strategy={} signal={} order={} amount={}",
                strategyKey, signalId, orderId, row.getReservedAmount());
        return row;
    }

    @Transactional
    public void releaseByOrder(UUID orderId, String reason) {
        repository.findByOrderIdAndDeletedFalse(orderId).ifPresent(r -> {
            if ("ACTIVE".equals(r.getStatus())) {
                r.setStatus("RELEASED");
                r.setReleaseReason(reason);
                repository.save(r);
                log.info("capital.released order={} reason={}", orderId, reason);
            }
        });
    }

    @Transactional
    public void releaseBySignal(UUID signalId, String reason) {
        repository.findBySignalIdAndStatusAndDeletedFalse(signalId, "ACTIVE").ifPresent(r -> {
            r.setStatus("RELEASED");
            r.setReleaseReason(reason);
            repository.save(r);
        });
    }
}
