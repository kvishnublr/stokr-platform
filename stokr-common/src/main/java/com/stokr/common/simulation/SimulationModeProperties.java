package com.stokr.common.simulation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * When {@link #enabled}, the platform runs against synthetic market data and simulated broker
 * execution while exercising the same signal → OMS → execution → outcome pipeline as production.
 */
@ConfigurationProperties(prefix = "stokr.simulation-mode")
public class SimulationModeProperties {

    /**
     * Must remain {@code false} in all deployed environments.
     * Simulation activates only via admin runtime toggle ({@link SimulationRuntimeControlService}).
     */
    private boolean enabled;

    /** Skip NSE/MCX session window checks on signal persist. */
    private boolean bypassSessionGuard = true;

    /** Skip catalog integrity / NIFTY opening gates. */
    private boolean bypassIntegrityGate = true;

    /** Skip post-restart warmup gate on catalog scans. */
    private boolean bypassSafeStartup = true;

    /** Route LIVE broker submissions through {@link #brokerVendor} adapter. */
    private boolean simulateBrokerExecution = true;

    /** Broker adapter vendor code (must match a registered {@code BrokerAdapter}). */
    private String brokerVendor = "SIMULATED";

    /** Default execution mode for harness-generated signals. */
    private String defaultExecutionMode = "PAPER";

    /** System user for catalog / harness signals. */
    private String systemUserId = "33333333-3333-3333-3333-333333333333";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isBypassSessionGuard() {
        return bypassSessionGuard;
    }

    public void setBypassSessionGuard(boolean bypassSessionGuard) {
        this.bypassSessionGuard = bypassSessionGuard;
    }

    public boolean isBypassIntegrityGate() {
        return bypassIntegrityGate;
    }

    public void setBypassIntegrityGate(boolean bypassIntegrityGate) {
        this.bypassIntegrityGate = bypassIntegrityGate;
    }

    public boolean isBypassSafeStartup() {
        return bypassSafeStartup;
    }

    public void setBypassSafeStartup(boolean bypassSafeStartup) {
        this.bypassSafeStartup = bypassSafeStartup;
    }

    public boolean isSimulateBrokerExecution() {
        return simulateBrokerExecution;
    }

    public void setSimulateBrokerExecution(boolean simulateBrokerExecution) {
        this.simulateBrokerExecution = simulateBrokerExecution;
    }

    public String getBrokerVendor() {
        return brokerVendor;
    }

    public void setBrokerVendor(String brokerVendor) {
        this.brokerVendor = brokerVendor;
    }

    public String getDefaultExecutionMode() {
        return defaultExecutionMode;
    }

    public void setDefaultExecutionMode(String defaultExecutionMode) {
        this.defaultExecutionMode = defaultExecutionMode;
    }

    public String getSystemUserId() {
        return systemUserId;
    }

    public void setSystemUserId(String systemUserId) {
        this.systemUserId = systemUserId;
    }
}
