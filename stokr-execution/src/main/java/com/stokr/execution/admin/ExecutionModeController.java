package com.stokr.execution.admin;

import com.stokr.oms.domain.ExecutionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Admin API for execution mode control.
 * Allows switching between LIVE, PAPER, HYBRID modes.
 */
@RestController
@RequestMapping("/api/admin/execution")
@RequiredArgsConstructor
@Slf4j
public class ExecutionModeController {

    private final ExecutionModeService executionModeService;

    @GetMapping("/mode")
    public ExecutionModeResponse getCurrentMode() {
        ExecutionMode mode = executionModeService.getCurrentMode();
        Instant lastSwitch = executionModeService.getLastSwitchTime();
        String lastSwitchedBy = executionModeService.getLastSwitchedBy();

        return ExecutionModeResponse.builder()
                .mode(mode)
                .lastSwitchTime(lastSwitch)
                .lastSwitchedBy(lastSwitchedBy)
                .availableModes(ExecutionMode.values())
                .build();
    }

    @PostMapping("/mode/{newMode}")
    public ExecutionModeResponse switchMode(
            @PathVariable String newMode,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String requestedBy) {

        ExecutionMode targetMode = ExecutionMode.valueOf(newMode.toUpperCase());

        executionModeService.switchMode(targetMode, reason, requestedBy);

        log.info("execution.mode.switched target={} reason={} by={}",
                targetMode, reason, requestedBy);

        return getCurrentMode();
    }

    @GetMapping("/health")
    public HealthReport getExecutionHealth() {
        return executionModeService.getHealthReport();
    }

    @GetMapping("/stats")
    public ExecutionStats getExecutionStats() {
        return executionModeService.getExecutionStats();
    }

    @PostMapping("/replay/start")
    public ReplayResponse startReplay(@RequestBody ReplayRequest request) {
        executionModeService.startReplay(
                request.getSymbol(),
                request.getStartTime(),
                request.getEndTime(),
                request.getSpeed()
        );

        return ReplayResponse.builder()
                .status("STARTED")
                .symbol(request.getSymbol())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .speed(request.getSpeed())
                .build();
    }

    @PostMapping("/replay/pause")
    public ReplayResponse pauseReplay() {
        executionModeService.pauseReplay();
        return ReplayResponse.builder().status("PAUSED").build();
    }

    @PostMapping("/replay/resume")
    public ReplayResponse resumeReplay() {
        executionModeService.resumeReplay();
        return ReplayResponse.builder().status("RESUMED").build();
    }

    @PostMapping("/replay/stop")
    public ReplayResponse stopReplay() {
        executionModeService.stopReplay();
        return ReplayResponse.builder().status("STOPPED").build();
    }

    @GetMapping("/replay/status")
    public ReplayStatus getReplayStatus() {
        return executionModeService.getReplayStatus();
    }

    /**
     * Responses.
     */
    @lombok.Data
    @lombok.Builder
    public static class ExecutionModeResponse {
        private ExecutionMode mode;
        private Instant lastSwitchTime;
        private String lastSwitchedBy;
        private ExecutionMode[] availableModes;
    }

    @lombok.Data
    @lombok.Builder
    public static class ReplayRequest {
        private String symbol;
        private Instant startTime;
        private Instant endTime;
        private Double speed;
    }

    @lombok.Data
    @lombok.Builder
    public static class ReplayResponse {
        private String status;
        private String symbol;
        private Instant startTime;
        private Instant endTime;
        private Double speed;
    }

    @lombok.Data
    public static class ReplayStatus {
        private String status;
        private Instant currentTime;
        private double progress;
        private double speed;
    }

    @lombok.Data
    public static class HealthReport {
        private String overallStatus;
        private Map<String, String> components = new HashMap<>();
        private Map<String, Long> latencies = new HashMap<>();
    }

    @lombok.Data
    public static class ExecutionStats {
        private long ordersToday;
        private long ordersFilled;
        private long ordersRejected;
        private double avgFillLatencyMs;
        private double avgSlippageBps;
        private double marginUtilization;
    }
}
