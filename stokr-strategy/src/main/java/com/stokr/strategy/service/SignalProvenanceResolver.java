package com.stokr.strategy.service;

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
        if ("PAPER".equals(mode) || "PAPER".equalsIgnoreCase(signal.getPipeline())) {
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
