package com.stokr.common.simulation;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SimulationModeService {

    private final SimulationModeProperties properties;
    private final SimulationRuntimeControlService runtimeControl;

    public SimulationModeService(
            SimulationModeProperties properties,
            SimulationRuntimeControlService runtimeControl
    ) {
        this.properties = properties;
        this.runtimeControl = runtimeControl;
    }

    /**
     * True only when an admin has explicitly enabled simulation at runtime.
     * Never derived from YAML / Spring profile alone.
     */
    public boolean isActive() {
        return runtimeControl.isRuntimeEnabled();
    }

    /** @deprecated use {@link #isActive()} */
    public boolean isEnabled() {
        return isActive();
    }

    public boolean bypassSessionGuard() {
        return isActive() && properties.isBypassSessionGuard();
    }

    public boolean bypassIntegrityGate() {
        return isActive() && properties.isBypassIntegrityGate();
    }

    public boolean bypassSafeStartup() {
        return isActive() && properties.isBypassSafeStartup();
    }

    public boolean simulateBrokerExecution() {
        return isActive() && properties.isSimulateBrokerExecution();
    }

    public String brokerVendor() {
        return properties.getBrokerVendor();
    }

    public String defaultExecutionMode() {
        return properties.getDefaultExecutionMode();
    }

    public UUID systemUserId() {
        return UUID.fromString(properties.getSystemUserId());
    }

    public SimulationModeProperties properties() {
        return properties;
    }
}
