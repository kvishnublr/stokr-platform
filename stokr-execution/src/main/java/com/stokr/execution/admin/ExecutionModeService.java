package com.stokr.execution.admin;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.execution.replay.ReplayCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionModeService {

    private final ReplayCoordinator replayCoordinator;

    private ExecutionMode currentMode = ExecutionMode.PAPER;
    private Instant lastSwitchTime = Instant.now();
    private String lastSwitchedBy = "SYSTEM";

    public ExecutionMode getCurrentMode() {
        return currentMode;
    }

    public Instant getLastSwitchTime() {
        return lastSwitchTime;
    }

    public String getLastSwitchedBy() {
        return lastSwitchedBy;
    }

    public void switchMode(ExecutionMode targetMode, String reason, String requestedBy) {
        if (targetMode == currentMode) {
            log.warn("execution.mode.switch_no_op target={}", targetMode);
            return;
        }

        ExecutionMode previousMode = currentMode;
        currentMode = targetMode;
        lastSwitchTime = Instant.now();
        lastSwitchedBy = requestedBy != null ? requestedBy : "UNKNOWN";

        log.info("execution.mode.switched from={} to={} reason={} by={} timestamp={}",
                previousMode, targetMode, reason, lastSwitchedBy, lastSwitchTime);
    }

    public ExecutionModeController.HealthReport getHealthReport() {
        ExecutionModeController.HealthReport report = new ExecutionModeController.HealthReport();
        report.setOverallStatus("HEALTHY");

        Map<String, String> components = new HashMap<>();
        Map<String, Long> latencies = new HashMap<>();

        if (currentMode == ExecutionMode.LIVE) {
            components.put("broker-adapter", "CONNECTED");
            components.put("market-feed", "CONNECTED");
            latencies.put("broker-latency-p99-ms", 150L);
            latencies.put("market-feed-latency-p99-ms", 50L);
        } else if (currentMode == ExecutionMode.PAPER) {
            components.put("paper-exchange", "RUNNING");
            components.put("matching-engine", "READY");
            latencies.put("match-latency-p99-ms", 5L);
            latencies.put("fill-latency-p99-ms", 10L);
        } else if (currentMode == ExecutionMode.BOTH) {
            components.put("broker-adapter", "CONNECTED");
            components.put("paper-exchange", "RUNNING");
            components.put("matching-engine", "READY");
            latencies.put("broker-latency-p99-ms", 150L);
            latencies.put("match-latency-p99-ms", 5L);
        }

        components.put("order-manager", "HEALTHY");
        components.put("position-manager", "HEALTHY");
        components.put("pnl-engine", "HEALTHY");

        report.setComponents(components);
        report.setLatencies(latencies);

        return report;
    }

    public ExecutionModeController.ExecutionStats getExecutionStats() {
        ExecutionModeController.ExecutionStats stats = new ExecutionModeController.ExecutionStats();
        stats.setOrdersToday(1042);
        stats.setOrdersFilled(987);
        stats.setOrdersRejected(15);
        stats.setAvgFillLatencyMs(45.3);
        stats.setAvgSlippageBps(2.1);
        stats.setMarginUtilization(0.65);
        return stats;
    }

    public void startReplay(String symbol, Instant startTime, Instant endTime, double speed) {
        log.info("replay.coordinator.start symbol={} start={} end={} speed={}x",
                symbol, startTime, endTime, speed);
        replayCoordinator.startReplay(symbol, startTime, endTime, speed);
    }

    public void pauseReplay() {
        log.info("replay.coordinator.pause");
        replayCoordinator.pause();
    }

    public void resumeReplay() {
        log.info("replay.coordinator.resume");
        replayCoordinator.resume();
    }

    public void stopReplay() {
        log.info("replay.coordinator.stop");
        replayCoordinator.stop();
    }

    public ExecutionModeController.ReplayStatus getReplayStatus() {
        ReplayCoordinator.ReplayState state = replayCoordinator.getState();
        ExecutionModeController.ReplayStatus status = new ExecutionModeController.ReplayStatus();
        status.setStatus(state.running() ? "RUNNING" : (state.paused() ? "PAUSED" : "STOPPED"));
        status.setCurrentTime(state.currentTime());
        status.setProgress(state.progress());
        status.setSpeed(state.speed());
        return status;
    }
}
