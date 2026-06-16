package com.stokr.strategy.service;

import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.lifecycle.ExitCategory;
import com.stokr.strategy.lifecycle.StrategyExitTelemetryService;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Marks open signals as {@link ExitCategory#MANUAL} when the operator closes a leg outside
 * the strategy exit pipeline (trader terminal, broker app, etc.) so auto-exit engines do not
 * emit duplicate exit orders.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalManualExitSuppressionService {

    private static final int LOOKBACK_DAYS = 14;
    private static final Set<OrderState> OPEN_ENTRY_STATES = Set.of(
            OrderState.FILLED,
            OrderState.PARTIALLY_FILLED,
            OrderState.ACCEPTED
    );
    private static final Set<OrderState> OPEN_EXIT_STATES = Set.of(
            OrderState.FILLED,
            OrderState.PARTIALLY_FILLED,
            OrderState.ACCEPTED,
            OrderState.SUBMITTED,
            OrderState.PENDING_SUBMISSION
    );

    private final StrategySignalRepository signalRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final StrategyExitTelemetryService exitTelemetryService;

    @Transactional
    public int suppressAutoExitForSymbol(UUID userId, String rawSymbol, String reason) {
        if (userId == null || rawSymbol == null || rawSymbol.isBlank()) {
            return 0;
        }
        String norm = normalizeSymbol(rawSymbol);
        Set<String> symbolVariants = symbolVariants(norm);
        Instant since = Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS);
        List<OmsOrder> entries = omsOrderRepository.findRecentFilledEntriesWithSignal(
                since, OPEN_ENTRY_STATES, PageRequest.of(0, 500));

        Set<UUID> signalIds = new LinkedHashSet<>();
        for (OmsOrder entry : entries) {
            if (entry.getUserId() == null || !userId.equals(entry.getUserId()) || entry.getSignalId() == null) {
                continue;
            }
            String entrySym = entry.getSymbol() != null ? normalizeSymbol(entry.getSymbol()) : "";
            if (!symbolVariants.contains(entrySym)) {
                continue;
            }
            if (hasOppositeExitAfter(entry)) {
                continue;
            }
            signalIds.add(entry.getSignalId());
        }

        if (signalIds.isEmpty()) {
            return 0;
        }

        int updated = 0;
        Instant now = Instant.now();
        String detail = reason != null && !reason.isBlank() ? reason : "MANUAL: operator_exit";
        for (StrategySignalEntity sig : signalRepository.findAllById(signalIds)) {
            if (sig.isDeleted() || Boolean.TRUE.equals(sig.getTestTrade())) {
                continue;
            }
            if (ExitCategory.isTerminalOutcome(sig.getOutcomeStatus())) {
                continue;
            }
            SignalLifecycleService.updateOutcome(sig, ExitCategory.MANUAL.outcomeStatus());
            sig.setOutcomeTime(now);
            sig.setExpiryReason(detail);
            if (sig.getRealizedPnl() == null && sig.getUnrealizedPnl() != null) {
                sig.setRealizedPnl(sig.getUnrealizedPnl());
            }
            sig.setUnrealizedPnl(null);
            signalRepository.save(sig);
            exitTelemetryService.recordExit(sig, ExitCategory.MANUAL, detail, now, null, null, false);
            updated++;
            log.info("signal.manual_exit_suppressed signalId={} symbol={} userId={}", sig.getId(), norm, userId);
        }
        return updated;
    }

    private boolean hasOppositeExitAfter(OmsOrder entry) {
        if (entry.getUserId() == null || entry.getSymbol() == null || entry.getSide() == null
                || entry.getCreatedAt() == null) {
            return false;
        }
        String exitSide = "BUY".equalsIgnoreCase(entry.getSide()) ? "SELL" : "BUY";
        if (omsOrderRepository.existsOppositeSideAfter(
                entry.getUserId(), entry.getSymbol(), exitSide, entry.getCreatedAt(), OPEN_EXIT_STATES)) {
            return true;
        }
        if (entry.getSignalId() == null) {
            return false;
        }
        return omsOrderRepository.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(entry.getSignalId()).stream()
                .anyMatch(o -> exitSide.equalsIgnoreCase(o.getSide())
                        && OPEN_EXIT_STATES.contains(o.getState())
                        && o.getCreatedAt() != null
                        && o.getCreatedAt().isAfter(entry.getCreatedAt()));
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        String t = symbol.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            return t;
        }
        if (!t.contains(":") && t.matches("[A-Z0-9._-]+")) {
            return "NSE:" + t;
        }
        return t;
    }

    private static Set<String> symbolVariants(String norm) {
        Set<String> out = new LinkedHashSet<>();
        if (norm == null || norm.isBlank()) {
            return out;
        }
        String upper = norm.trim().toUpperCase(Locale.ROOT);
        out.add(upper);
        if (upper.contains(":")) {
            out.add(upper.substring(upper.indexOf(':') + 1));
        } else {
            out.add("NSE:" + upper);
            out.add("BSE:" + upper);
        }
        return out;
    }
}
