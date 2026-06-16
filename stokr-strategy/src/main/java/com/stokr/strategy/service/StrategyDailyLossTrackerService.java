package com.stokr.strategy.service;

import com.stokr.common.events.StrategyPnlUpdateEvent;
import com.stokr.strategy.domain.StrategyDailyPnlSnapshot;
import com.stokr.strategy.repository.StrategyDailyPnlSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyDailyLossTrackerService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final StrategyDailyPnlSnapshotRepository snapshotRepository;

    public BigDecimal getTodayPnl(String strategyKey) {
        LocalDate today = LocalDate.now(IST);
        return snapshotRepository.findByStrategyKeyAndBusinessDateAndDeletedFalse(strategyKey, today)
                .map(StrategyDailyPnlSnapshot::getRealizedPnl)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional
    @EventListener
    public void onPnlUpdate(StrategyPnlUpdateEvent event) {
        recordFill(event.strategyKey(), event.realizedPnlDelta());
    }

    @Transactional
    public void recordFill(String strategyKey, BigDecimal realizedPnlDelta) {
        if (strategyKey == null || realizedPnlDelta == null) return;
        LocalDate today = LocalDate.now(IST);
        int updated = snapshotRepository.addRealizedPnl(strategyKey, today, realizedPnlDelta);
        if (updated == 0) {
            StrategyDailyPnlSnapshot snap = new StrategyDailyPnlSnapshot();
            snap.setStrategyKey(strategyKey);
            snap.setBusinessDate(today);
            snap.setRealizedPnl(realizedPnlDelta);
            snap.setTradeCount(1);
            snapshotRepository.save(snap);
            log.debug("pnl_tracker.created strategyKey={} date={} pnl={}", strategyKey, today, realizedPnlDelta);
        } else {
            log.debug("pnl_tracker.updated strategyKey={} date={} delta={}", strategyKey, today, realizedPnlDelta);
        }
    }
}
