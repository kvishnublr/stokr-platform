package com.stokr.execution.guard;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExecutionGuardService {

    private final ExecutionGuardPolicyService policyService;
    private final MarketSnapshotService marketSnapshotService;
    private final com.stokr.execution.broker.BrokerPositionTruthService brokerPositionTruthService;

    public ExecutionGuardResult validate(ExecutionContext ctx) {
        long startNs = System.nanoTime();
        ExecutionGuardPolicy policy = policyService.resolve(ctx.strategyKey(), ctx.symbol(), ctx.guardMode());
        MarketSnapshot market = marketSnapshotService.capture(ctx.symbol(), ctx.now());
        List<ExecutionGuardViolation> violations = new ArrayList<>();

        validateSession(ctx, market, violations);
        validateSignalTtl(ctx, policy, violations);
        validateDrift(ctx, policy, market, violations);
        validateFeedFreshness(ctx, policy, market, violations);
        validateSpread(policy, market, violations);
        validateLiquidity(policy, market, violations);
        validateVolatility(policy, market, violations);
        validateCircuit(market, violations);
        validateSlippage(policy, market, violations);
        if (ctx.executionMode() == com.stokr.oms.domain.ExecutionMode.LIVE) {
            violations.addAll(brokerPositionTruthService.validateForExecution(
                    ctx.userId(), ctx.symbol(), ctx.side(), ctx.guardMode(), ctx.now()));
        }

        ExecutionGuardOutcome outcome = decideOutcome(ctx, policy, violations);
        long latencyMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        return new ExecutionGuardResult(outcome, policy, market, List.copyOf(violations), latencyMs);
    }

    private static void validateSignalTtl(ExecutionContext ctx, ExecutionGuardPolicy policy, List<ExecutionGuardViolation> out) {
        if (ctx.signalMeta() == null || ctx.signalMeta().signalGeneratedAt() == null) return;
        long age = Duration.between(ctx.signalMeta().signalGeneratedAt(), ctx.now()).toMillis();
        if (age > policy.maxSignalAgeMs()) {
            out.add(new ExecutionGuardViolation(
                    "SIGNAL_EXPIRED",
                    ExecutionGuardSeverity.CRITICAL,
                    "Signal expired",
                    "Signal expired by " + (age - policy.maxSignalAgeMs()) + " ms"
            ));
        }
    }

    private static void validateDrift(ExecutionContext ctx, ExecutionGuardPolicy policy, MarketSnapshot market, List<ExecutionGuardViolation> out) {
        BigDecimal ref = ctx.signalMeta() != null ? ctx.signalMeta().signalReferencePrice() : null;
        BigDecimal ltp = market.ltp();
        if (ref == null || ltp == null || ref.compareTo(BigDecimal.ZERO) <= 0) return;
        BigDecimal drift = ltp.subtract(ref).abs().multiply(BigDecimal.valueOf(100)).divide(ref, 6, RoundingMode.HALF_UP);
        if (drift.compareTo(policy.maxDriftPct()) > 0) {
            out.add(new ExecutionGuardViolation(
                    "DRIFT_EXCEEDED",
                    ExecutionGuardSeverity.CRITICAL,
                    "Entry drift exceeded",
                    drift + "% > " + policy.maxDriftPct() + "%"
            ));
        } else if (drift.compareTo(policy.maxDriftPct().multiply(new BigDecimal("0.8"))) > 0) {
            out.add(new ExecutionGuardViolation(
                    "DRIFT_NEAR_LIMIT",
                    ExecutionGuardSeverity.WARNING,
                    "Drift close to limit",
                    drift + "%"
            ));
        }
    }

    private static void validateFeedFreshness(ExecutionContext ctx, ExecutionGuardPolicy policy, MarketSnapshot market, List<ExecutionGuardViolation> out) {
        if (market.lastTickAt() == null) {
            out.add(new ExecutionGuardViolation("NO_TICK", ExecutionGuardSeverity.CRITICAL, "No live tick available", "Tick stream unavailable"));
            return;
        }
        long staleMs = Math.max(0L, Duration.between(market.lastTickAt(), ctx.now()).toMillis());
        boolean wsOpen = market.websocketState() != null && market.websocketState().toUpperCase().contains("OPEN");
        if (!wsOpen && ctx.guardMode() == ExecutionGuardMode.ENTRY_STRICT) {
            out.add(new ExecutionGuardViolation("WS_DISCONNECTED", ExecutionGuardSeverity.CRITICAL, "Feed websocket disconnected", "WS state " + market.websocketState()));
        }
        if (staleMs > policy.hardStaleFeedMs()) {
            if (ctx.guardMode() == ExecutionGuardMode.EXIT_SAFE && policy.allowExitDuringStaleFeed()) {
                out.add(new ExecutionGuardViolation("STALE_FEED_EXIT_SAFE", ExecutionGuardSeverity.WARNING, "Stale feed; exit allowed by policy", staleMs + " ms"));
            } else {
                out.add(new ExecutionGuardViolation("STALE_FEED_HARD", ExecutionGuardSeverity.CRITICAL, "Stale market feed", staleMs + " ms"));
            }
        } else if (staleMs > policy.softStaleFeedMs()) {
            out.add(new ExecutionGuardViolation("STALE_FEED_SOFT", ExecutionGuardSeverity.WARNING, "Feed delay detected", staleMs + " ms"));
        }
    }

    private static void validateSpread(ExecutionGuardPolicy policy, MarketSnapshot market, List<ExecutionGuardViolation> out) {
        if (market.bestBid() == null || market.bestAsk() == null || market.bestBid().compareTo(BigDecimal.ZERO) <= 0) {
            out.add(new ExecutionGuardViolation("SPREAD_NOT_AVAILABLE", ExecutionGuardSeverity.INFO, "Spread unavailable", "Bid/ask not present in snapshot"));
            return;
        }
        BigDecimal spreadPct = market.bestAsk().subtract(market.bestBid())
                .multiply(BigDecimal.valueOf(100))
                .divide(market.bestBid(), 6, RoundingMode.HALF_UP);
        if (spreadPct.compareTo(policy.maxSpreadPct()) > 0) {
            out.add(new ExecutionGuardViolation("SPREAD_TOO_WIDE", ExecutionGuardSeverity.CRITICAL, "Spread too wide", spreadPct + "%"));
        }
    }

    private static void validateLiquidity(ExecutionGuardPolicy policy, MarketSnapshot market, List<ExecutionGuardViolation> out) {
        if (market.topBidQty() == null || market.topAskQty() == null) {
            out.add(new ExecutionGuardViolation("LIQUIDITY_NOT_AVAILABLE", ExecutionGuardSeverity.INFO, "Depth unavailable", "Top-of-book quantity not present"));
            return;
        }
        BigDecimal minDepth = market.topBidQty().min(market.topAskQty());
        if (minDepth.compareTo(policy.minLiquidityQty()) < 0) {
            out.add(new ExecutionGuardViolation("LOW_LIQUIDITY", ExecutionGuardSeverity.CRITICAL, "Liquidity too low", "Depth " + minDepth));
        }
    }

    private static void validateVolatility(ExecutionGuardPolicy policy, MarketSnapshot market, List<ExecutionGuardViolation> out) {
        if (market.volatilityPct() == null) return;
        if (market.volatilityPct().compareTo(policy.volatilityThresholdPct()) > 0) {
            out.add(new ExecutionGuardViolation("VOLATILITY_SPIKE", ExecutionGuardSeverity.WARNING, "Volatility spike detected", market.volatilityPct() + "%"));
        }
    }

    private static void validateCircuit(MarketSnapshot market, List<ExecutionGuardViolation> out) {
        if (market.ltp() == null || market.ltp().compareTo(BigDecimal.ZERO) <= 0) return;
        // Placeholder flag using synthetic rule until full circuit feed is wired.
        if (market.volatilityPct() != null && market.volatilityPct().compareTo(new BigDecimal("8.0")) > 0) {
            out.add(new ExecutionGuardViolation("CIRCUIT_RISK", ExecutionGuardSeverity.WARNING, "Possible freeze/circuit proximity", "High intraday movement"));
        }
    }

    private static void validateSlippage(ExecutionGuardPolicy policy, MarketSnapshot market, List<ExecutionGuardViolation> out) {
        if (market.estimatedSlippagePct() == null) return;
        if (market.estimatedSlippagePct().compareTo(policy.maxSlippagePct()) > 0) {
            out.add(new ExecutionGuardViolation("SLIPPAGE_EXCEEDED", ExecutionGuardSeverity.CRITICAL, "Estimated slippage exceeds budget", market.estimatedSlippagePct() + "%"));
        }
    }

    private static void validateSession(ExecutionContext ctx, MarketSnapshot market, List<ExecutionGuardViolation> out) {
        if (!market.marketOpen() && ctx.guardMode() == ExecutionGuardMode.ENTRY_STRICT) {
            out.add(new ExecutionGuardViolation("SESSION_CLOSED", ExecutionGuardSeverity.CRITICAL, "Market session not open", "Entry blocked outside market hours"));
        }
    }

    private static ExecutionGuardOutcome decideOutcome(ExecutionContext ctx, ExecutionGuardPolicy policy, List<ExecutionGuardViolation> violations) {
        boolean hard = violations.stream().anyMatch(v -> v.severity() == ExecutionGuardSeverity.CRITICAL);
        boolean soft = violations.stream().anyMatch(v -> v.severity() == ExecutionGuardSeverity.WARNING);
        if (hard) return ExecutionGuardOutcome.HARD_FAIL;
        if (soft) return policy.allowSoftFailExecution() || ctx.guardMode() == ExecutionGuardMode.EXIT_SAFE
                ? ExecutionGuardOutcome.SOFT_FAIL : ExecutionGuardOutcome.HARD_FAIL;
        return ExecutionGuardOutcome.PASS;
    }
}

