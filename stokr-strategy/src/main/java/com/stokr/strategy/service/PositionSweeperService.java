package com.stokr.strategy.service;

import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PositionSweeperService {

    private static final Set<String> TERMINAL_OUTCOMES = Set.of(
            "TARGET_HIT", "STOPLOSS_HIT", "SL_HIT", "BREAKEVEN_EXIT",
            "PRESSURE_EXIT", "LIQUIDITY_PROTECTION", "FEED_PROTECTION", "TIME_EXIT"
    );

    private final OmsOrderRepository omsOrderRepository;
    private final StrategySignalRepository strategySignalRepository;

    @Value("${stokr.strategy.position-sweep.orphan-max-age-hours:2}")
    private int orphanMaxAgeHours;

    @Value("${stokr.strategy.position-sweep.stuck-signal-hours:6}")
    private int stuckSignalHours;

    @Value("${stokr.strategy.position-sweep.aged-signal-hours:24}")
    private int agedSignalHours;

    @Value("${stokr.strategy.position-sweep.terminal-outcome-lookback-hours:72}")
    private int terminalLookbackHours;

    @Scheduled(fixedDelayString = "${stokr.strategy.position-sweep.interval-ms:300000}")
    @Transactional
    public void sweep() {
        log.debug("PositionSweeper starting");
        int total = 0;
        total += sweepOrphanOrders();
        total += sweepStuckSignals();
        total += sweepAgedSignals();
        total += sweepMissingExitLegs();
        if (total > 0) {
            log.warn("position_sweeper.total_actions={}", total);
        } else {
            log.debug("PositionSweeper nothing to do");
        }
    }

    int sweepOrphanOrders() {
        Instant maxAge = Instant.now().minus(orphanMaxAgeHours, ChronoUnit.HOURS);
        List<OmsOrder> orphans = omsOrderRepository.findFilledOrdersWithNullSignalId(maxAge);

        List<OmsOrder> toClose = orphans.stream()
                .filter(o -> !hasLaterExitOrder(o))
                .collect(Collectors.toList());

        if (toClose.isEmpty()) {
            return 0;
        }

        for (OmsOrder o : toClose) {
            o.setState(OrderState.CANCELLED);
            o.setRejectReason("POSITION_SWEEP: orphan order no signal linkage, stale from " + o.getCreatedAt());
        }
        log.warn("position_sweeper.orphan_orders_closed count={}", toClose.size());
        return toClose.size();
    }

    int sweepStuckSignals() {
        Instant maxAge = Instant.now().minus(stuckSignalHours, ChronoUnit.HOURS);
        List<StrategySignalEntity> stuck = strategySignalRepository.findRunningSignalsCreatedBefore(maxAge);

        if (stuck.isEmpty()) {
            return 0;
        }

        for (StrategySignalEntity s : stuck) {
            SignalLifecycleService.updateOutcome(s, "TIME_EXIT");
            s.setOutcomeComment("POSITION_SWEEP: signal stuck >" + stuckSignalHours + "h, force-closed TIME_EXIT");
            s.setOutcomeTime(Instant.now());
        }
        log.warn("position_sweeper.stuck_signals_closed count={}", stuck.size());
        return stuck.size();
    }

    int sweepAgedSignals() {
        Instant maxAge = Instant.now().minus(agedSignalHours, ChronoUnit.HOURS);
        List<StrategySignalEntity> aged = strategySignalRepository.findNonTerminalSignalsCreatedBefore(maxAge, TERMINAL_OUTCOMES);

        if (aged.isEmpty()) {
            return 0;
        }

        for (StrategySignalEntity s : aged) {
            SignalLifecycleService.updateOutcome(s, "TIME_EXIT");
            s.setOutcomeComment("POSITION_SWEEP: signal >" + agedSignalHours + "h stale, force-closed TIME_EXIT");
            s.setOutcomeTime(Instant.now());
        }
        log.warn("position_sweeper.aged_signals_closed count={}", aged.size());
        return aged.size();
    }

    int sweepMissingExitLegs() {
        Instant maxOrderAge = Instant.now().minus(orphanMaxAgeHours, ChronoUnit.HOURS);
        Instant outcomeSince = Instant.now().minus(terminalLookbackHours, ChronoUnit.HOURS);
        List<String> outcomes = List.copyOf(TERMINAL_OUTCOMES);

        List<OmsOrder> stale = omsOrderRepository.findFilledOrdersWithTerminatedSignalNoExit(
                maxOrderAge, outcomes, outcomeSince);

        if (stale.isEmpty()) {
            return 0;
        }

        for (OmsOrder o : stale) {
            o.setState(OrderState.CANCELLED);
            o.setRejectReason("POSITION_SWEEP: signal terminated but no exit leg created, closed");
        }
        log.warn("position_sweeper.missing_exit_legs_closed count={}", stale.size());
        return stale.size();
    }

    private boolean hasLaterExitOrder(OmsOrder order) {
        if (order.getUserId() == null || order.getSymbol() == null || order.getSide() == null) {
            return false;
        }
        String exitSide = "BUY".equals(order.getSide()) ? "SELL" : "BUY";
        return omsOrderRepository.existsOppositeSideAfter(
                order.getUserId(),
                order.getSymbol(),
                exitSide,
                order.getCreatedAt(),
                List.of(OrderState.FILLED, OrderState.PARTIALLY_FILLED, OrderState.ACCEPTED, OrderState.SUBMITTED)
        );
    }
}
