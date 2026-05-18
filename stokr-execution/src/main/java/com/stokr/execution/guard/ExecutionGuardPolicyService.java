package com.stokr.execution.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

@Service
public class ExecutionGuardPolicyService {

    @Value("${stokr.execution.guard.default.max-signal-age-ms:5000}")
    private long maxSignalAgeMs;
    @Value("${stokr.execution.guard.default.max-drift-pct:0.10}")
    private BigDecimal maxDriftPct;
    @Value("${stokr.execution.guard.default.max-spread-pct:0.20}")
    private BigDecimal maxSpreadPct;
    @Value("${stokr.execution.guard.default.max-slippage-pct:0.15}")
    private BigDecimal maxSlippagePct;
    @Value("${stokr.execution.guard.default.soft-stale-feed-ms:5000}")
    private long softStaleFeedMs;
    @Value("${stokr.execution.guard.default.hard-stale-feed-ms:15000}")
    private long hardStaleFeedMs;
    @Value("${stokr.execution.guard.default.volatility-threshold-pct:1.20}")
    private BigDecimal volatilityThresholdPct;
    @Value("${stokr.execution.guard.default.min-liquidity-qty:1}")
    private BigDecimal minLiquidityQty;
    @Value("${stokr.execution.guard.default.allow-exit-during-stale-feed:true}")
    private boolean allowExitDuringStaleFeed;
    @Value("${stokr.execution.guard.default.allow-soft-fail-execution:false}")
    private boolean allowSoftFailExecution;
    @Value("${stokr.execution.guard.default.regime:DEFAULT}")
    private String defaultRegime;

    private final ExecutionGuardPolicyRegistryService registryService;

    public ExecutionGuardPolicyService(ExecutionGuardPolicyRegistryService registryService) {
        this.registryService = registryService;
    }

    public ExecutionGuardPolicy resolve(String strategyKey, String symbol, ExecutionGuardMode mode) {
        ExecutionGuardPolicy base = new ExecutionGuardPolicy(
                maxSignalAgeMs,
                maxDriftPct,
                maxSpreadPct,
                maxSlippagePct,
                softStaleFeedMs,
                hardStaleFeedMs,
                volatilityThresholdPct,
                minLiquidityQty,
                allowExitDuringStaleFeed,
                allowSoftFailExecution
        );
        if (mode == ExecutionGuardMode.EXIT_SAFE) {
            return new ExecutionGuardPolicy(
                    base.maxSignalAgeMs() * 3,
                    base.maxDriftPct().multiply(new BigDecimal("2.0")),
                    base.maxSpreadPct().multiply(new BigDecimal("2.0")),
                    base.maxSlippagePct().multiply(new BigDecimal("2.0")),
                    base.softStaleFeedMs(),
                    base.hardStaleFeedMs(),
                    base.volatilityThresholdPct().multiply(new BigDecimal("1.5")),
                    base.minLiquidityQty(),
                    true,
                    true
            );
        }
        String k = strategyKey == null ? "" : strategyKey.toUpperCase();
        if (k.contains("SCALP") || k.contains("VWAP")) {
            return new ExecutionGuardPolicy(
                    Math.min(base.maxSignalAgeMs(), 3000L),
                    new BigDecimal("0.03"),
                    new BigDecimal("0.08"),
                    new BigDecimal("0.05"),
                    base.softStaleFeedMs(),
                    base.hardStaleFeedMs(),
                    new BigDecimal("0.90"),
                    base.minLiquidityQty(),
                    base.allowExitDuringStaleFeed(),
                    base.allowSoftFailExecution()
            );
        }
        if (k.contains("MOMENTUM") || k.contains("BREAKOUT")) {
            base = new ExecutionGuardPolicy(
                    Math.min(base.maxSignalAgeMs(), 8000L),
                    new BigDecimal("0.10"),
                    new BigDecimal("0.18"),
                    new BigDecimal("0.10"),
                    base.softStaleFeedMs(),
                    base.hardStaleFeedMs(),
                    new BigDecimal("1.50"),
                    base.minLiquidityQty(),
                    base.allowExitDuringStaleFeed(),
                    base.allowSoftFailExecution()
            );
        }
        String sessionKey = detectSessionKey();
        String regimeKey = defaultRegime == null ? "DEFAULT" : defaultRegime.trim().toUpperCase(Locale.ROOT);
        return registryService.applyOverrides(base, strategyKey, symbol, mode, sessionKey, regimeKey);
    }

    public void reloadPolicies() {
        registryService.reload();
    }

    private static String detectSessionKey() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        int hhmm = now.getHour() * 100 + now.getMinute();
        if (hhmm < 915) return "PRE_MARKET";
        if (hhmm <= 1030) return "OPENING";
        if (hhmm <= 1400) return "MIDDAY";
        if (hhmm <= 1530) return "CLOSING";
        return "POST_MARKET";
    }
}
