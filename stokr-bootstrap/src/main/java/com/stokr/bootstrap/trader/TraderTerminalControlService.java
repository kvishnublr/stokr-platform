package com.stokr.bootstrap.trader;

import com.stokr.common.exception.BadRequestException;
import com.stokr.execution.dto.CreateOrderRequest;
import com.stokr.execution.service.OrderPlacementService;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.service.StrategyInstanceLifecycleService;
import com.stokr.user.service.TraderExecutionModePreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class TraderTerminalControlService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(2);
    private static final Collection<OrderState> CANCELLABLE = List.of(
            OrderState.CREATED,
            OrderState.VALIDATED,
            OrderState.RISK_CHECK,
            OrderState.PENDING_SUBMISSION,
            OrderState.SUBMITTED,
            OrderState.ACCEPTED,
            OrderState.PARTIALLY_FILLED
    );

    private final OmsOrderRepository omsOrderRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final StrategyInstanceLifecycleService strategyInstanceLifecycleService;
    private final PortfolioPositionRepository portfolioPositionRepository;
    private final TraderExecutionModePreferenceService executionModePreferenceService;
    private final OrderPlacementService orderPlacementService;

    private final Map<String, PendingPlan> pendingPlans = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Map<String, Object> preview(UUID userId, String rawAction) {
        String action = normalize(rawAction);
        cleanupExpired();

        List<OmsOrder> pendingOrders = omsOrderRepository.findAllByUserIdAndDeletedFalseAndStateIn(userId, CANCELLABLE);
        List<StrategyInstance> runningStrategies = strategyInstanceRepository.findAllForUserWithDefinition(userId).stream()
                .filter(si -> "RUNNING".equalsIgnoreCase(si.getRuntimeState()))
                .toList();
        long openPositions = portfolioPositionRepository.findByUserIdAndDeletedFalse(userId).stream()
                .filter(p -> p.getQuantity() != null && p.getQuantity().signum() != 0)
                .count();

        List<String> impactedOrderIds = pendingOrders.stream().map(o -> o.getId().toString()).toList();
        List<String> impactedStrategyIds = runningStrategies.stream().map(s -> s.getId().toString()).toList();

        String token = UUID.randomUUID().toString();
        pendingPlans.put(token, new PendingPlan(
                token,
                userId,
                action,
                Instant.now(),
                impactedOrderIds,
                impactedStrategyIds,
                openPositions
        ));
        return Map.of(
                "action", action,
                "supported", true,
                "confirmationToken", token,
                "expiresAt", Instant.now().plus(TOKEN_TTL).toString(),
                "impact", Map.of(
                        "pendingOrderCount", impactedOrderIds.size(),
                        "runningStrategyCount", impactedStrategyIds.size(),
                        "openPositionCount", openPositions,
                        "sampleOrderIds", impactedOrderIds.stream().limit(10).toList(),
                        "sampleStrategyIds", impactedStrategyIds.stream().limit(10).toList()
                )
        );
    }

    @Transactional
    public Map<String, Object> execute(UUID userId, String token) {
        cleanupExpired();
        PendingPlan plan = pendingPlans.remove(token);
        if (plan == null || !plan.userId().equals(userId)) {
            throw new BadRequestException("Invalid or expired confirmation token");
        }
        if (Duration.between(plan.createdAt(), Instant.now()).compareTo(TOKEN_TTL) > 0) {
            throw new BadRequestException("Confirmation token expired");
        }

        List<String> notes = new ArrayList<>();
        int changedOrders = 0;
        int pausedStrategies = 0;
        List<Map<String, Object>> flattenResults = new ArrayList<>();

        switch (plan.action()) {
            case "PAUSE_TRADING", "DISABLE_NEW_ENTRIES" -> {
                executionModePreferenceService.update(userId, "PAPER", true);
                notes.add("execution mode set to PAPER");
            }
            case "RESUME_TRADING" -> {
                executionModePreferenceService.update(userId, "LIVE", true);
                notes.add("execution mode set to LIVE");
            }
            case "CANCEL_ALL_PENDING" -> {
                changedOrders += cancelPendingOrders(userId, notes);
            }
            case "EMERGENCY_KILL_SWITCH" -> {
                executionModePreferenceService.update(userId, "PAPER", true);
                changedOrders += cancelPendingOrders(userId, notes);
                pausedStrategies += pauseRunningStrategies(userId, notes);
                notes.add("new entries disabled");
            }
            case "EXIT_ALL", "FLATTEN_POSITIONS" -> {
                changedOrders += cancelPendingOrders(userId, notes);
                changedOrders += flattenOpenPositions(userId, notes, flattenResults);
                if ("EXIT_ALL".equals(plan.action())) {
                    pausedStrategies += pauseRunningStrategies(userId, notes);
                    executionModePreferenceService.update(userId, "PAPER", true);
                    notes.add("strategy runtime paused and new entries disabled");
                }
            }
            default -> throw new BadRequestException("Unsupported action: " + plan.action());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", plan.action());
        out.put("ok", true);
        out.put("ordersUpdated", changedOrders);
        out.put("strategiesPaused", pausedStrategies);
        out.put("flattenResults", flattenResults);
        out.put("notes", notes);
        out.put("executedAt", Instant.now().toString());
        return out;
    }

    private int cancelPendingOrders(UUID userId, List<String> notes) {
        int changed = 0;
        List<OmsOrder> rows = omsOrderRepository.findAllByUserIdAndDeletedFalseAndStateIn(userId, CANCELLABLE);
        for (OmsOrder o : rows) {
            try {
                OrderState st = o.getState();
                if (st == OrderState.ACCEPTED || st == OrderState.PARTIALLY_FILLED) {
                    orderLifecycleService.transition(o.getId(), OrderState.CANCEL_REQUESTED, "Cancelled by operator");
                    changed++;
                } else if (st == OrderState.RISK_CHECK || st == OrderState.PENDING_SUBMISSION || st == OrderState.SUBMITTED) {
                    orderLifecycleService.transition(o.getId(), OrderState.REJECTED, "Cancelled by operator");
                    changed++;
                } else {
                    notes.add("order " + o.getId() + " in state " + st + " not cancellable by policy");
                }
            } catch (Exception ex) {
                notes.add("order " + o.getId() + " cancel failed: " + ex.getClass().getSimpleName());
            }
        }
        return changed;
    }

    private int pauseRunningStrategies(UUID userId, List<String> notes) {
        int n = 0;
        List<StrategyInstance> rows = strategyInstanceRepository.findAllForUserWithDefinition(userId);
        for (StrategyInstance si : rows) {
            if (!"RUNNING".equalsIgnoreCase(si.getRuntimeState())) {
                continue;
            }
            try {
                strategyInstanceLifecycleService.pause(userId, si.getId());
                n++;
            } catch (Exception ex) {
                notes.add("strategy " + si.getId() + " pause failed: " + ex.getClass().getSimpleName());
            }
        }
        return n;
    }

    private int flattenOpenPositions(UUID userId, List<String> notes, List<Map<String, Object>> flattenResults) {
        int created = 0;
        ExecutionMode mode = resolveExecutionMode(userId);
        List<PortfolioPosition> positions = portfolioPositionRepository.findByUserIdAndDeletedFalse(userId);
        for (PortfolioPosition p : positions) {
            if (p.getQuantity() == null || p.getQuantity().signum() == 0) {
                continue;
            }
            String side = p.getQuantity().signum() > 0 ? "SELL" : "BUY";
            try {
                OmsOrder o = orderPlacementService.place(userId, new CreateOrderRequest(
                        p.getSymbol(),
                        side,
                        "MARKET",
                        p.getQuantity().abs(),
                        null,
                        mode,
                        mode == ExecutionMode.LIVE ? "ZERODHA" : "SIM",
                        "TERMINAL_FLATTEN",
                        "terminal:flatten:" + userId + ":" + p.getSymbol() + ":" + Instant.now().toEpochMilli()
                ));
                created++;
                flattenResults.add(Map.of(
                        "symbol", p.getSymbol(),
                        "side", side,
                        "qty", p.getQuantity().abs().toPlainString(),
                        "orderId", o.getId().toString(),
                        "state", o.getState().name(),
                        "mode", mode.name()
                ));
            } catch (Exception ex) {
                notes.add("flatten failed " + p.getSymbol() + ": " + ex.getClass().getSimpleName());
                flattenResults.add(Map.of(
                        "symbol", p.getSymbol(),
                        "side", side,
                        "qty", p.getQuantity().abs().toPlainString(),
                        "state", "FAILED",
                        "error", ex.getClass().getSimpleName()
                ));
            }
        }
        return created;
    }

    private ExecutionMode resolveExecutionMode(UUID userId) {
        String m = executionModePreferenceService.get(userId).executionMode();
        if ("LIVE".equalsIgnoreCase(m)) {
            return ExecutionMode.LIVE;
        }
        if ("PAPER".equalsIgnoreCase(m)) {
            return ExecutionMode.PAPER;
        }
        return ExecutionMode.SIMULATED;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("action is required");
        }
        return raw.trim().toUpperCase();
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        pendingPlans.entrySet().removeIf(e -> Duration.between(e.getValue().createdAt(), now).compareTo(TOKEN_TTL) > 0);
    }

    private record PendingPlan(
            String token,
            UUID userId,
            String action,
            Instant createdAt,
            List<String> orderIds,
            List<String> strategyIds,
            long openPositionCount
    ) {
    }
}
