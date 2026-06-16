package com.stokr.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StrategyCreateRequest(

        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "^[A-Z0-9_]+$", message = "strategy_key must be UPPER_SNAKE_CASE (A-Z, 0-9, underscore)")
        String strategyKey,

        @NotBlank
        @Size(max = 200)
        String displayName,

        @Size(max = 500)
        String description,

        /** INTRADAY | SWING | POSITIONAL | SCALPING */
        String strategyType,

        /** ALL | LIVE_ONLY | PAPER_ONLY | BACKTEST_ONLY */
        String executionMode,

        /** EQUITY | COMMODITY | FUTURES | OPTIONS | CURRENCY */
        String assetClass,

        /** NSE | NFO | MCX | CDS | BSE */
        String segment,

        /** Default candle timeframe, e.g. 1m, 5m, 15m */
        String defaultTimeframe,

        String defaultExchange,

        Boolean supportsBacktest,
        Boolean supportsLive,
        Boolean supportsPaper,

        Boolean derivativeEnabled,
        Boolean futuresStrategyEnabled,
        Boolean optionStrategyEnabled,

        /** LOW | MEDIUM | HIGH */
        String riskLevel,

        /** If provided, uses this as the generated class name instead of deriving from strategyKey */
        @Size(max = 256)
        String templateClassName,

        /** Whether to immediately generate the Java strategy template file on creation */
        Boolean generateTemplate,

        /** Specific symbols this strategy is scoped to, e.g. ["RELIANCE","TCS"]. Null = all symbols in bound universe. */
        java.util.List<String> symbols
) {}
