package com.stokr.backtest.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.backtest.engine.MeanReversionReplayService;
import com.stokr.backtest.service.BacktestReplayOutcome;
import com.stokr.backtest.service.BacktestRunQueryService;
import com.stokr.backtest.web.dto.BacktestRunSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Tag(name = "Backtest")
public class BacktestController {

    private final MeanReversionReplayService meanReversionReplayService;
    private final BacktestRunQueryService backtestRunQueryService;

    @PostMapping("/replay")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Deterministic synchronous replay for catalog strategies")
    public ApiResponse<BacktestReplayOutcome> replay(
            @AuthenticationPrincipal StokrUserDetails user,
            @Valid @RequestBody ReplayRequest request
    ) {
        UUID uid = request.userId() != null ? request.userId() : user.getId();
        BacktestReplayOutcome outcome = meanReversionReplayService.runReplay(
                request.symbol(),
                request.start(),
                request.end(),
                uid,
                request.seed(),
                request.strategyKey(),
                request.timeframe(),
                request.executionProfile()
        );
        return ApiResponse.ok(outcome, CorrelationIdHolder.get());
    }

    @PostMapping("/runs/{runId}/resume")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Resume interrupted backtest after checkpoint validation")
    public ApiResponse<BacktestReplayOutcome> resume(@PathVariable("runId") UUID runId) {
        BacktestReplayOutcome outcome = meanReversionReplayService.resumeReplay(runId);
        return ApiResponse.ok(outcome, CorrelationIdHolder.get());
    }

    @GetMapping("/runs")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Page<BacktestRunSummaryDto>> runs(
            @AuthenticationPrincipal StokrUserDetails user,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(backtestRunQueryService.pageForUser(user.getId(), pageable), CorrelationIdHolder.get());
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BacktestReplayOutcome> runDetail(
            @AuthenticationPrincipal StokrUserDetails user,
            @PathVariable("runId") UUID runId
    ) {
        return ApiResponse.ok(backtestRunQueryService.detailForUser(runId, user.getId()), CorrelationIdHolder.get());
    }

    /** @deprecated use POST /replay */
    @PostMapping("/mean-reversion/replay")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BacktestReplayOutcome> replayLegacy(@AuthenticationPrincipal StokrUserDetails user,
                                                           @Valid @RequestBody ReplayRequest request) {
        return replay(user, request);
    }

    /** @deprecated use POST /runs/{runId}/resume */
    @PostMapping("/mean-reversion/resume/{runId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BacktestReplayOutcome> resumeLegacy(@PathVariable("runId") UUID runId) {
        return resume(runId);
    }
}
