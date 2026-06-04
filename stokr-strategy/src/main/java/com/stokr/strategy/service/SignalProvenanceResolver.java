package com.stokr.strategy.service;

import com.stokr.common.simulation.SimulationScenarioContext;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.signals.SignalProvenance;
import org.springframework.stereotype.Component;

/**
 * Resolves persisted signal provenance so analytics stay separated by class.
 */
@Component
public class SignalProvenanceResolver {

    public SignalProvenance resolve(StrategySignalEntity signal, String executionMode) {
        if (signal == null) {
            return SignalProvenance.LIVE;
        }
        if (signal.isSimulation() || SimulationScenarioContext.active()) {
            return SignalProvenance.SIMULATION;
        }
        if (Boolean.TRUE.equals(signal.getTestTrade())) {
            return SignalProvenance.LAB;
        }
        if (signal.getBacktestRunId() != null) {
            return SignalProvenance.REPLAY;
        }
        if (signal.getSignalSource() != null) {
            return signal.getSignalSource();
        }
        String mode = executionMode != null ? executionMode.trim().toUpperCase() : "";
        String pipeline = signal.getPipeline() != null ? signal.getPipeline().trim().toUpperCase() : "";
        if ("BOTH".equals(mode) || "BOTH".equals(pipeline)) {
            return SignalProvenance.LIVE;
        }
        if ("PAPER".equals(mode) || "PAPER".equals(pipeline)) {
            return SignalProvenance.PAPER;
        }
        return SignalProvenance.LIVE;
    }

    public void apply(StrategySignalEntity signal, SignalProvenance source) {
        if (signal != null && source != null) {
            signal.setSignalSource(source);
        }
    }

    public void applyForPersist(StrategySignalEntity signal, String executionMode, SignalProvenance explicit) {
        if (signal == null) {
            return;
        }
        SignalProvenance resolved = explicit != null ? explicit : resolve(signal, executionMode);
        signal.setSignalSource(resolved);
    }
}
