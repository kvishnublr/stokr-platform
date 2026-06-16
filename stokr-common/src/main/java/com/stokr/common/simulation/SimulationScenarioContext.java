package com.stokr.common.simulation;

/**
 * Per-run scenario state for simulated broker outcomes and price paths.
 * Set by the E2E harness before pipeline execution; cleared after the run.
 */
public final class SimulationScenarioContext {

    private static final ThreadLocal<Holder> CURRENT = new ThreadLocal<>();

    private SimulationScenarioContext() {
    }

    public static void set(SimulationScenario scenario, SimulatedBrokerOutcome brokerOutcome, java.util.UUID runId) {
        CURRENT.set(new Holder(scenario, brokerOutcome, runId));
    }

    public static void set(SimulationScenario scenario, SimulatedBrokerOutcome brokerOutcome) {
        set(scenario, brokerOutcome, null);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static SimulationScenario scenario() {
        Holder h = CURRENT.get();
        return h != null ? h.scenario : null;
    }

    public static SimulatedBrokerOutcome brokerOutcome() {
        Holder h = CURRENT.get();
        return h != null && h.brokerOutcome != null ? h.brokerOutcome : SimulatedBrokerOutcome.FILLED;
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static java.util.UUID runId() {
        Holder h = CURRENT.get();
        return h != null ? h.runId : null;
    }

    private record Holder(SimulationScenario scenario, SimulatedBrokerOutcome brokerOutcome, java.util.UUID runId) {
    }
}
