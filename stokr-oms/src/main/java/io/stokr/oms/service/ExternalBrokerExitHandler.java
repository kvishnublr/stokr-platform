package io.stokr.oms.service;

import io.stokr.oms.domain.entity.ManualExitSuppression;
import io.stokr.oms.domain.entity.PositionLifecycleAudit;
import io.stokr.oms.repository.ManualExitSuppressionRepository;
import io.stokr.oms.repository.PositionLifecycleAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ExternalBrokerExitHandler {

    private final ManualExitSuppressionRepository suppressionRepository;
    private final PositionLifecycleAuditRepository auditRepository;

    @Transactional
    public void handleManualBrokerExit(UUID positionId, UUID userId, String symbol,
                                       String manualExitSource, BigDecimal exitQuantity, BigDecimal exitPrice) {

        log.info("Manual broker exit detected: position={} source={} qty={} price={}",
            positionId, manualExitSource, exitQuantity, exitPrice);

        // Create suppression record
        var suppression = ManualExitSuppression.builder()
            .positionId(positionId)
            .userId(userId)
            .symbol(symbol)
            .suppressSlExit(true)
            .suppressTargetExit(true)
            .suppressPressureExit(true)
            .suppressFeedProtectionExit(true)
            .suppressAutoExit(true)
            .suppressAllExits(true)
            .manualExitSource(manualExitSource)
            .manualExitTime(LocalDateTime.now())
            .manualExitQuantity(exitQuantity)
            .manualExitPrice(exitPrice)
            .isPartialExit(false)
            .reason("User manually closed from " + manualExitSource)
            .build();

        suppressionRepository.save(suppression);

        // Record audit trail
        var audit = PositionLifecycleAudit.builder()
            .userId(userId)
            .positionId(positionId)
            .symbol(symbol)
            .oldState("OPEN")
            .newState("CLOSED")
            .ownerType("MANUAL")
            .exitSource("MANUAL_BROKER")
            .triggeredBy("EXTERNAL_BROKER_EXIT_HANDLER")
            .reason("User manually closed from " + manualExitSource)
            .manualExitQuantity(exitQuantity)
            .manualExitPrice(exitPrice)
            .occurredAt(LocalDateTime.now())
            .build();

        auditRepository.save(audit);

        log.info("Manual exit suppression activated for position={}", positionId);
    }

    public boolean isManualExitSuppressed(UUID positionId) {
        return suppressionRepository.findByPositionId(positionId)
            .map(ManualExitSuppression::getSuppressAllExits)
            .orElse(false);
    }

    public boolean canExecuteExit(UUID positionId, String exitType) {
        var suppression = suppressionRepository.findByPositionId(positionId);
        if (suppression.isEmpty()) {
            return true;
        }

        var record = suppression.get();
        return switch(exitType) {
            case "SL" -> !record.getSuppressSlExit();
            case "TARGET" -> !record.getSuppressTargetExit();
            case "PRESSURE" -> !record.getSuppressPressureExit();
            case "FEED" -> !record.getSuppressFeedProtectionExit();
            case "AUTO" -> !record.getSuppressAutoExit();
            default -> !record.getSuppressAllExits();
        };
    }

    @Transactional
    public void removeSuppression(UUID positionId) {
        suppressionRepository.deleteByPositionId(positionId);
        log.info("Manual exit suppression removed for position={}", positionId);
    }
}
