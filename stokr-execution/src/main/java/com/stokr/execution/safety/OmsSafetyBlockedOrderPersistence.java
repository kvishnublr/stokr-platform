package com.stokr.execution.safety;

import com.stokr.strategy.domain.StrategySignalEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists safety blocks in an independent transaction so a rolled-back parent
 * execution path can still record audit rows.
 */
@Service
@RequiredArgsConstructor
public class OmsSafetyBlockedOrderPersistence {

    private final OmsSafetyBlockedOrderRepository blockedOrderRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(
            StrategySignalEntity signal,
            UUID userId,
            String requested,
            String effective,
            String code,
            String message) {
        OmsSafetyBlockedOrder row = new OmsSafetyBlockedOrder();
        row.setUserId(userId);
        if (signal != null) {
            row.setSignalId(signal.getId());
            row.setStrategyName(signal.getStrategyName());
            row.setSymbol(signal.getSymbol());
        }
        row.setRequestedMode(requested);
        row.setEffectiveMode(effective);
        row.setBlockCode(code);
        row.setBlockMessage(message);
        row.setCreatedAt(Instant.now());
        blockedOrderRepository.save(row);
    }
}
