package com.stokr.backtest.service;

import com.stokr.backtest.domain.ReplayTerminalDiagnosis;

/**
 * Derives {@link ReplayTerminalDiagnosis} from persisted replay outcome + loop counters.
 */
public final class ReplayDiagnosisClassifier {

    private ReplayDiagnosisClassifier() {
    }

    public static ReplayTerminalDiagnosis classifySuccess(BacktestReplayOutcome outcome) {
        if (outcome == null) {
            return ReplayTerminalDiagnosis.FAILED;
        }
        var report = outcome.validation();
        ReplayLoopTelemetry loop = outcome.loopTelemetry();
        int processed = loop != null ? loop.candlesProcessed() : 0;
        int expected = loop != null ? loop.candlesExpected() : 0;
        long sig = report != null ? report.strategySignalCount() : 0L;
        long exec = report != null ? report.executionEventCount() : 0L;
        int trades = outcome.metrics() != null ? outcome.metrics().totalTrades() : 0;
        if (trades > 0 || exec > 0) {
            return ReplayTerminalDiagnosis.COMPLETED;
        }
        if (sig > 0) {
            return ReplayTerminalDiagnosis.EXECUTION_BLOCKED;
        }
        if (expected > 0 && processed == 0) {
            return ReplayTerminalDiagnosis.EMPTY_REPLAY;
        }
        if (processed > 0) {
            return ReplayTerminalDiagnosis.NO_SIGNALS;
        }
        return ReplayTerminalDiagnosis.EMPTY_REPLAY;
    }

    public static ReplayTerminalDiagnosis fromFailureMessage(String message) {
        if (message == null) {
            return ReplayTerminalDiagnosis.FAILED;
        }
        String m = message.toLowerCase();
        if (m.contains("no market data") || m.contains("no candles")) {
            return ReplayTerminalDiagnosis.NO_DATA;
        }
        return ReplayTerminalDiagnosis.FAILED;
    }
}
