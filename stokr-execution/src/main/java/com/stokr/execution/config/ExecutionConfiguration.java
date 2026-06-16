package com.stokr.execution.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Centralized execution system configuration.
 * All parameters externalized to application.yml for runtime control.
 *
 * Properties are read via @ConfigurationProperties binding from:
 * execution:
 *   mode: PAPER|LIVE|HYBRID
 *   paper:
 *     startingCapital: 1000000.00
 *     slippageBps: 2.0
 *     latencyMs: 5-15 range
 *   replay:
 *     enabled: true
 *     defaultSpeed: 1.0
 *   synthetic:
 *     enabled: true
 */
@Configuration
@ConfigurationProperties(prefix = "execution")
@Data
public class ExecutionConfiguration {

    private String mode = "PAPER";  // PAPER, LIVE, HYBRID

    private Paper paper = new Paper();
    private Replay replay = new Replay();
    private Synthetic synthetic = new Synthetic();
    private Safety safety = new Safety();
    private Broker broker = new Broker();

    @Data
    public static class Paper {
        private BigDecimal startingCapital = BigDecimal.valueOf(1_000_000);
        private boolean marginEnabled = true;
        private double marginMultiplier = 1.0;  // leverage factor
        private double slippageBps = 2.0;  // basis points for slippage
        private double slippageSize = 0.01;  // order size threshold
        private long latencyMinMs = 5;
        private long latencyMaxMs = 15;
        private boolean partialFillsEnabled = true;
        private double partialFillRatio = 0.8;  // 80% fill ratio
    }

    @Data
    public static class Replay {
        private boolean enabled = true;
        private double defaultSpeed = 1.0;
        private double minSpeed = 0.1;
        private double maxSpeed = 10.0;
        private long tickIntervalMs = 1000;
        private boolean allowSyntheticFallback = true;
    }

    @Data
    public static class Synthetic {
        private boolean enabled = true;
        private String defaultModel = "random_walk";  // random_walk, volatility_clustering, trend_reversion, gaps_spikes
        private double volatility = 0.02;  // 2% volatility
        private double meanReversionFactor = 0.02;
        private double gapProbability = 0.02;  // 2% chance of gap
        private double spikeProbability = 0.01;  // 1% chance of spike
        private boolean deterministic = false;  // use seed for reproducibility
        private long randomSeed = 42L;  // seed if deterministic
    }

    @Data
    public static class Safety {
        private boolean isolationEnforced = true;
        private boolean preExecutionCheckEnabled = true;
        private boolean postExecutionCheckEnabled = true;
        private boolean timeSourceValidationEnabled = true;
        private boolean reconciliationEnabled = true;
        private double reconciliationDriftThresholdBps = 50.0;  // 0.5% drift
        private long reconciliationIntervalSeconds = 60;
    }

    @Data
    public static class Broker {
        private String primaryBroker = "ZERODHA";  // ZERODHA, FYERS, DHAN
        private long connectionTimeoutMs = 5000;
        private long requestTimeoutMs = 10000;
        private int maxRetries = 3;
        private long retryDelayMs = 1000;
        private boolean circuitBreakerEnabled = true;
        private double circuitBreakerThreshold = 0.5;  // fail if 50%+ requests fail
        private long circuitBreakerResetMs = 60000;  // 1 minute
    }

    // Audit trail configuration
    public record AuditConfig(
            boolean eventJournalEnabled,
            boolean executionLoggingEnabled,
            long eventRetentionDays
    ) {}

    public AuditConfig getAuditConfig() {
        return new AuditConfig(true, true, 90);
    }

    // Validation
    public void validate() {
        if (paper.getStartingCapital().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Paper starting capital must be positive");
        }

        if (paper.getSlippageBps() < 0) {
            throw new IllegalArgumentException("Slippage basis points cannot be negative");
        }

        if (replay.getDefaultSpeed() < replay.getMinSpeed() || replay.getDefaultSpeed() > replay.getMaxSpeed()) {
            throw new IllegalArgumentException("Default replay speed must be within min/max bounds");
        }

        if (synthetic.getVolatility() < 0) {
            throw new IllegalArgumentException("Synthetic volatility cannot be negative");
        }
    }
}
