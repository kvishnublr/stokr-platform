package com.stokr.execution.service;

import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.execution.dto.CreateOrderRequest;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.reconciliation.ReconciliationEvent;
import com.stokr.oms.reconciliation.ReconciliationEventRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.strategy.service.SignalManualExitSuppressionService;
import com.stokr.user.service.TraderExecutionModePreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PositionExitOrchestratorService {

    private static final Collection<OrderState> CANCELLABLE = List.of(
            OrderState.CREATED,
            OrderState.VALIDATED,
            OrderState.RISK_CHECK,
            OrderState.PENDING_SUBMISSION,
            OrderState.SUBMITTED,
            OrderState.ACCEPTED,
            OrderState.PARTIALLY_FILLED
    );

    private static final Duration ORPHAN_RECON_FALLBACK = Duration.ofHours(2);

    private final OmsOrderRepository omsOrderRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final PortfolioPositionRepository portfolioPositionRepository;
    private final TraderExecutionModePreferenceService executionModePreferenceService;
    private final OrderPlacementService orderPlacementService;
    private final BrokerPositionTruthService brokerPositionTruthService;
    private final SignalManualExitSuppressionService manualExitSuppressionService;
    private final ReconciliationEventRepository reconciliationEventRepository;

    @Transactional
    public Map<String, Object> flattenAll(UUID userId, String actionKey, String reason) {
        return flatten(userId, null, actionKey, reason, true);
    }

    @Transactional
    public Map<String, Object> flattenSegment(UUID userId, String segment, String actionKey, String reason) {
        return flatten(userId, segment, actionKey, reason, true);
    }

    @Transactional
    public Map<String, Object> flattenSymbol(UUID userId, String rawSymbol, String actionKey, String reason) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String symbol = BrokerPositionTruthService.normalizeSymbol(rawSymbol);
        return flatten(userId, null, actionKey, reason, false, symbol);
    }

    private Map<String, Object> flatten(
            UUID userId,
            String segment,
            String actionKey,
            String reason,
            boolean cancelAllPending
    ) {
        return flatten(userId, segment, actionKey, reason, cancelAllPending, null);
    }

    private Map<String, Object> flatten(
            UUID userId,
            String segment,
            String actionKey,
            String reason,
            boolean cancelAllPending,
            String symbolOnly
    ) {
        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> flattenResults = new ArrayList<>();

        if (cancelAllPending) {
            notes.add("cancelled " + cancelPendingOrders(userId, null, notes) + " pending order(s)");
        } else if (symbolOnly != null) {
            notes.add("cancelled " + cancelPendingOrders(userId, symbolOnly, notes) + " pending order(s) for " + symbolOnly);
        }

        int created = 0;
        brokerPositionTruthService.syncUser(userId);
        BrokerPositionTruthSnapshot snap = brokerPositionTruthService.snapshot(userId);
        List<FlattenTarget> targets = collectTargets(userId, segment, symbolOnly, snap, notes);
        for (FlattenTarget target : targets) {
            try {
                OmsOrder order = placeExit(userId, target, actionKey, reason);
                created++;
                flattenResults.add(Map.of(
                        "symbol", target.symbol(),
                        "exchange", target.exchange() != null ? target.exchange() : "",
                        "qty", target.qty().abs().toPlainString(),
                        "side", target.qty().signum() > 0 ? "SELL" : "BUY",
                        "mode", order.getExecutionMode() != null ? order.getExecutionMode().name() : "",
                        "orderId", order.getId().toString(),
                        "state", order.getState().name()
                ));
                try {
                    int suppressed = manualExitSuppressionService.suppressAutoExitForSymbol(
                            userId, target.symbol(), "MANUAL: " + actionKey);
                    if (suppressed > 0) {
                        notes.add("suppressed auto-exit for " + suppressed + " signal(s) on " + target.symbol());
                    }
                } catch (Exception suppressEx) {
                    notes.add("suppress auto-exit failed " + target.symbol() + ": "
                            + suppressEx.getClass().getSimpleName());
                }
            } catch (Exception ex) {
                notes.add("exit failed " + target.symbol() + ": " + ex.getClass().getSimpleName());
                flattenResults.add(Map.of(
                        "symbol", target.symbol(),
                        "exchange", target.exchange() != null ? target.exchange() : "",
                        "qty", target.qty().abs().toPlainString(),
                        "side", target.qty().signum() > 0 ? "SELL" : "BUY",
                        "state", "FAILED",
                        "error", ex.getClass().getSimpleName()
                ));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("action", actionKey);
        out.put("segment", segment);
        out.put("reason", reason);
        out.put("ordersCreated", created);
        out.put("results", flattenResults);
        out.put("notes", notes);
        out.put("executedAt", Instant.now().toString());
        return out;
    }

    private OmsOrder placeExit(UUID userId, FlattenTarget target, String actionKey, String reason) {
        BigDecimal qty = target.qty().abs();
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("quantity is zero");
        }
        String side = target.qty().signum() > 0 ? "SELL" : "BUY";
        ExecutionMode exitMode = target.fromBroker() ? ExecutionMode.LIVE : resolveExecutionMode(userId);
        String exitBroker = exitMode == ExecutionMode.LIVE ? "ZERODHA" : "SIM";
        String safeAction = actionKey == null || actionKey.isBlank() ? "EXIT_SAFE" : actionKey.trim();
        OmsOrder order = orderPlacementService.place(userId, new CreateOrderRequest(
                target.symbol(),
                side,
                "MARKET",
                qty,
                null,
                exitMode,
                exitBroker,
                safeAction,
                safeAction + ":" + userId + ":" + target.symbol(),
                null,
                null,
                null,
                null,
                true,
                "EXIT_SAFE",
                false,
                null
        ));
        log.info("position_exit.placed action={} userId={} symbol={} exchange={} side={} qty={} mode={} orderId={} state={}",
                safeAction, userId, target.symbol(), target.exchange(), side, qty.toPlainString(),
                exitMode, order.getId(), order.getState());
        return order;
    }

    private List<FlattenTarget> collectTargets(
            UUID userId,
            String segment,
            String symbolOnly,
            BrokerPositionTruthSnapshot snap,
            List<String> notes
    ) {
        Map<String, FlattenTarget> bySymbol = new LinkedHashMap<>();
        if (symbolOnly != null) {
            FlattenTarget target = resolveSymbolTarget(userId, symbolOnly, snap, segment);
            if (target != null) {
                bySymbol.put(target.symbol(), target);
            }
            return List.copyOf(bySymbol.values());
        }

        if (snap.brokerConnected() && snap.lastSyncAt() != null) {
            for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow row : snap.positions()) {
                if (row.brokerQty() == null || row.brokerQty().signum() == 0) {
                    continue;
                }
                if (!matchesSegment(segment, row.exchange(), row.symbol())) {
                    continue;
                }
                String norm = BrokerPositionTruthService.normalizeSymbol(row.symbol());
                bySymbol.putIfAbsent(norm, new FlattenTarget(norm, row.brokerQty(), row.exchange(), row.product(), true));
            }
        }

        if (bySymbol.isEmpty()) {
            for (PortfolioPosition p : portfolioPositionRepository.findByUserIdAndDeletedFalse(userId)) {
                if (p.getQuantity() == null || p.getQuantity().signum() == 0) {
                    continue;
                }
                String norm = BrokerPositionTruthService.normalizeSymbol(p.getSymbol());
                if (!matchesSegment(segment, null, norm)) {
                    continue;
                }
                bySymbol.putIfAbsent(norm, new FlattenTarget(norm, p.getQuantity(), null, null, false));
            }
        }

        if (bySymbol.isEmpty()) {
            bySymbol.putAll(orphanReconciliationTargets(userId, segment, notes));
        }

        return List.copyOf(bySymbol.values());
    }

    private FlattenTarget resolveSymbolTarget(UUID userId, String rawSymbol, BrokerPositionTruthSnapshot snap, String segment) {
        String symbol = BrokerPositionTruthService.normalizeSymbol(rawSymbol);
        if (snap.brokerConnected() && snap.lastSyncAt() != null) {
            for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow row : snap.positions()) {
                String rowSymbol = BrokerPositionTruthService.normalizeSymbol(row.symbol());
                if (symbol.equalsIgnoreCase(rowSymbol)
                        && row.brokerQty() != null
                        && row.brokerQty().signum() != 0
                        && matchesSegment(segment, row.exchange(), rowSymbol)) {
                    return new FlattenTarget(rowSymbol, row.brokerQty(), row.exchange(), row.product(), true);
                }
            }
        }
        for (PortfolioPosition p : portfolioPositionRepository.findByUserIdAndDeletedFalse(userId)) {
            String rowSymbol = BrokerPositionTruthService.normalizeSymbol(p.getSymbol());
            if (symbol.equalsIgnoreCase(rowSymbol)
                    && p.getQuantity() != null
                    && p.getQuantity().signum() != 0
                    && matchesSegment(segment, null, rowSymbol)) {
                return new FlattenTarget(rowSymbol, p.getQuantity(), null, null, false);
            }
        }
        return null;
    }

    private Map<String, FlattenTarget> orphanReconciliationTargets(UUID userId, String segment, List<String> notes) {
        Instant since = Instant.now().minus(ORPHAN_RECON_FALLBACK);
        Map<String, FlattenTarget> bySymbol = new LinkedHashMap<>();
        for (ReconciliationEvent event : reconciliationEventRepository.findRecentOrphanBrokerPositions(userId, since)) {
            if (event.getSymbol() == null || event.getBrokerQty() == null || event.getBrokerQty().signum() == 0) {
                continue;
            }
            String symbol = BrokerPositionTruthService.normalizeSymbol(event.getSymbol());
            if (!matchesSegment(segment, null, symbol)) {
                continue;
            }
            bySymbol.putIfAbsent(symbol, new FlattenTarget(symbol, event.getBrokerQty(), null, null, true));
        }
        if (!bySymbol.isEmpty()) {
            notes.add("using " + bySymbol.size() + " orphan broker position(s) from reconciliation fallback");
            log.warn("position_exit.recon_fallback user={} segment={} symbols={}", userId, segment, bySymbol.size());
        }
        return bySymbol;
    }

    private int cancelPendingOrders(UUID userId, String rawSymbol, List<String> notes) {
        String symbol = rawSymbol == null ? null : BrokerPositionTruthService.normalizeSymbol(rawSymbol);
        int changed = 0;
        List<OmsOrder> rows = omsOrderRepository.findAllByUserIdAndDeletedFalseAndStateIn(userId, CANCELLABLE);
        for (OmsOrder order : rows) {
            if (symbol != null && !symbol.equals(BrokerPositionTruthService.normalizeSymbol(order.getSymbol()))) {
                continue;
            }
            try {
                OrderState st = order.getState();
                if (st == OrderState.ACCEPTED || st == OrderState.PARTIALLY_FILLED) {
                    orderLifecycleService.transition(order.getId(), OrderState.CANCEL_REQUESTED, "Cancelled by operator");
                    changed++;
                } else if (st == OrderState.RISK_CHECK || st == OrderState.PENDING_SUBMISSION || st == OrderState.SUBMITTED) {
                    orderLifecycleService.transition(order.getId(), OrderState.REJECTED, "Cancelled by operator");
                    changed++;
                } else {
                    notes.add("order " + order.getId() + " in state " + st + " not cancellable by policy");
                }
            } catch (Exception ex) {
                notes.add("order " + order.getId() + " cancel failed: " + ex.getClass().getSimpleName());
            }
        }
        return changed;
    }

    private ExecutionMode resolveExecutionMode(UUID userId) {
        String mode = executionModePreferenceService.get(userId).executionMode();
        if ("LIVE".equalsIgnoreCase(mode)) {
            return ExecutionMode.LIVE;
        }
        if ("PAPER".equalsIgnoreCase(mode)) {
            return ExecutionMode.PAPER;
        }
        return ExecutionMode.SIMULATED;
    }

    private static boolean matchesSegment(String segment, String exchange, String symbol) {
        if (segment == null || segment.isBlank()) {
            return true;
        }
        String seg = segment.trim().toUpperCase(Locale.ROOT);
        String ex = exchange == null ? "" : exchange.trim().toUpperCase(Locale.ROOT);
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if ("MCX".equals(seg)) {
            return ex.contains("MCX") || isMcxSymbol(sym);
        }
        if ("NSE".equals(seg)) {
            return ex.isEmpty() || ex.contains("NSE") || ex.contains("BSE") || !isMcxSymbol(sym);
        }
        if ("BSE".equals(seg)) {
            return ex.contains("BSE");
        }
        if ("CDS".equals(seg) || "CURRENCY".equals(seg)) {
            return ex.contains("CDS") || ex.contains("CUR") || sym.contains("USD") || sym.contains("EUR")
                    || sym.contains("JPY") || sym.contains("GBP");
        }
        return ex.contains(seg);
    }

    private static boolean isMcxSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.startsWith("CRUDEOIL")
                || s.startsWith("NATURALGAS")
                || s.startsWith("GOLD")
                || s.startsWith("SILVER")
                || s.startsWith("COPPER")
                || s.startsWith("ALUMINIUM")
                || s.startsWith("ALUMINUM")
                || s.startsWith("ZINC")
                || s.startsWith("LEAD")
                || s.startsWith("NICKEL")
                || s.startsWith("MENTHAOIL")
                || s.startsWith("COTTON")
                || s.startsWith("CARDAMOM");
    }

    private record FlattenTarget(String symbol, BigDecimal qty, String exchange, String product, boolean fromBroker) {}
}
