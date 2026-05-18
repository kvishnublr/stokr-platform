package com.stokr.bootstrap.trader;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.execution.guard.ExecutionQualityScoringService;
import com.stokr.execution.guard.ExecutionTimelineService;
import com.stokr.marketdata.domain.MarketdataCandle;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Trader execution client APIs — central market store and replay visuals are accessed only through this facade.
 */
@RestController
@RequestMapping("/api/trader")
@Tag(name = "Trader execution client")
public class TraderExecutionClientController {

    private final TraderTerminalViewService traderTerminalViewService;
    private final TraderTerminalControlService traderTerminalControlService;
    private final IntradayReadinessService intradayReadinessService;
    private final ExecutionGuardStreamService executionGuardStreamService;
    private final ExecutionTimelineService executionTimelineService;
    private final ExecutionQualityScoringService executionQualityScoringService;

    public TraderExecutionClientController(
            TraderTerminalViewService traderTerminalViewService,
            TraderTerminalControlService traderTerminalControlService,
            IntradayReadinessService intradayReadinessService,
            ExecutionGuardStreamService executionGuardStreamService,
            ExecutionTimelineService executionTimelineService,
            ExecutionQualityScoringService executionQualityScoringService
    ) {
        this.traderTerminalViewService = traderTerminalViewService;
        this.traderTerminalControlService = traderTerminalControlService;
        this.intradayReadinessService = intradayReadinessService;
        this.executionGuardStreamService = executionGuardStreamService;
        this.executionTimelineService = executionTimelineService;
        this.executionQualityScoringService = executionQualityScoringService;
    }

    @GetMapping("/terminal/market/watch")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Watchlist projection (central cache — trader plane)")
    public ApiResponse<List<Map<String, Object>>> marketWatch() {
        return ApiResponse.ok(traderTerminalViewService.marketWatchProjection(), CorrelationIdHolder.get());
    }

    @GetMapping("/terminal/market/chart")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Chart OHLC series derived from central candle store (trader plane)")
    public ApiResponse<List<Map<String, Object>>> marketChart(
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "interval", defaultValue = "5m") String interval,
            @RequestParam(value = "timeframe", required = false) String timeframe,
            @RequestParam(value = "limit", defaultValue = "120") int limit
    ) {
        String tf = timeframe != null && !timeframe.isBlank() ? timeframe : interval;
        return ApiResponse.ok(traderTerminalViewService.chartSeries(symbol, tf, limit), CorrelationIdHolder.get());
    }

    @GetMapping("/terminal/replay/candles-range")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Candles for replay visualization (same store as admin; trader must not call /api/marketdata)")
    public ApiResponse<List<MarketdataCandle>> replayCandlesRange(
            @RequestParam("symbol") String symbol,
            @RequestParam(value = "timeframe", defaultValue = "1m") String timeframe,
            @RequestParam("start") Instant start,
            @RequestParam("end") Instant end
    ) {
        return ApiResponse.ok(traderTerminalViewService.replayCandlesRange(symbol, timeframe, start, end), CorrelationIdHolder.get());
    }

    @GetMapping("/strategy-feed")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Recent strategy signals for this trader (signal-centric, not raw market)")
    public ApiResponse<List<Map<String, Object>>> strategyFeed(
            @AuthenticationPrincipal StokrUserDetails user,
            @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(traderTerminalViewService.strategyFeed(user.getId(), limit), CorrelationIdHolder.get());
    }

    @GetMapping("/execution-summary")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "OMS-focused execution snapshot for this trader")
    public ApiResponse<Map<String, Object>> executionSummary(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(traderTerminalViewService.executionSummary(user.getId()), CorrelationIdHolder.get());
    }

    @GetMapping("/replay-summary")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Latest async replay job telemetry + diagnosis for this trader")
    public ApiResponse<Map<String, Object>> replaySummary(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(traderTerminalViewService.replaySummary(user.getId()), CorrelationIdHolder.get());
    }

    @GetMapping("/terminal/workstation")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Institutional trader workstation snapshot (positions, parity, risk, execution, signals)")
    public ApiResponse<Map<String, Object>> terminalWorkstation(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(traderTerminalViewService.terminalWorkstation(user.getId()), CorrelationIdHolder.get());
    }

    @GetMapping(value = "/terminal/execution-guard/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Realtime execution guard event stream")
    public SseEmitter executionGuardStream(@AuthenticationPrincipal StokrUserDetails user) {
        return executionGuardStreamService.subscribe(user.getId());
    }

    @GetMapping("/terminal/execution-guard/timeline")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Recent execution timeline events")
    public ApiResponse<List<Map<String, Object>>> executionGuardTimeline(
            @AuthenticationPrincipal StokrUserDetails user,
            @RequestParam(value = "limit", defaultValue = "200") int limit
    ) {
        return ApiResponse.ok(executionTimelineService.recentForUser(user.getId(), limit), CorrelationIdHolder.get());
    }

    @GetMapping("/terminal/execution-guard/quality-score")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Execution quality score for terminal")
    public ApiResponse<Map<String, Object>> executionGuardQualityScore(
            @AuthenticationPrincipal StokrUserDetails user
    ) {
        return ApiResponse.ok(executionQualityScoringService.scoreForUser(user.getId()), CorrelationIdHolder.get());
    }

    @GetMapping("/intraday/readiness")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Aggregated intraday operational readiness (feed, broker, runtime, strategies, historical)")
    public ApiResponse<IntradayReadinessService.IntradayReadinessResponse> intradayReadiness(
            @AuthenticationPrincipal StokrUserDetails user
    ) {
        return ApiResponse.ok(intradayReadinessService.readiness(user.getId()), CorrelationIdHolder.get());
    }

    @org.springframework.web.bind.annotation.PostMapping("/intraday/readiness/actions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Run intraday readiness recovery action with cooldown and audit logging")
    public ApiResponse<IntradayReadinessService.ReadinessActionResult> intradayReadinessAction(
            @AuthenticationPrincipal StokrUserDetails user,
            @org.springframework.web.bind.annotation.RequestBody IntradayReadinessService.IntradayReadinessActionRequest body
    ) {
        return ApiResponse.ok(
                intradayReadinessService.runAction(user.getId(), body.action(), body.strategyKey()),
                CorrelationIdHolder.get()
        );
    }

    public record TraderTerminalActionRequest(String action, String confirmationToken) {
    }

    @GetMapping("/terminal/control/preview")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Preview impact of trader control action and mint short-lived confirmation token")
    public ApiResponse<Map<String, Object>> terminalControlPreview(
            @AuthenticationPrincipal StokrUserDetails user,
            @RequestParam("action") String action
    ) {
        return ApiResponse.ok(traderTerminalControlService.preview(user.getId(), action), CorrelationIdHolder.get());
    }

    @org.springframework.web.bind.annotation.PostMapping("/terminal/control/execute")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Execute trader control action with server-side confirmation token")
    public ApiResponse<Map<String, Object>> terminalControlExecute(
            @AuthenticationPrincipal StokrUserDetails user,
            @org.springframework.web.bind.annotation.RequestBody TraderTerminalActionRequest body
    ) {
        return ApiResponse.ok(
                traderTerminalControlService.execute(user.getId(), body.confirmationToken()),
                CorrelationIdHolder.get()
        );
    }

    @org.springframework.web.bind.annotation.PostMapping("/terminal/orders/{orderId}/cancel")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Cancel single OMS order from terminal orders grid")
    public ApiResponse<Map<String, Object>> cancelTerminalOrder(
            @AuthenticationPrincipal StokrUserDetails user,
            @PathVariable("orderId") java.util.UUID orderId
    ) {
        return ApiResponse.ok(traderTerminalControlService.cancelOrder(user.getId(), orderId), CorrelationIdHolder.get());
    }
}
