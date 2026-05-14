package com.stokr.backtest.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.backtest.engine.MeanReversionReplayService;
import com.stokr.backtest.service.BacktestJobEnqueueService;
import com.stokr.backtest.service.BacktestReplayOutcome;
import com.stokr.backtest.service.BacktestRunQueryService;
import com.stokr.backtest.validation.StrategyExecutionRequestValidator;
import com.stokr.backtest.web.dto.BacktestJobStatusDto;
import com.stokr.backtest.web.dto.BacktestRunSummaryDto;
import com.stokr.backtest.web.dto.ExecutionRequestDto;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.dto.metadata.StrategyMetadataResponseDto;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Tag(name = "Backtest")
public class BacktestController {

    private final MeanReversionReplayService meanReversionReplayService;
    private final BacktestRunQueryService backtestRunQueryService;
    private final StrategyExecutionRequestValidator strategyExecutionRequestValidator;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final BacktestJobEnqueueService backtestJobEnqueueService;

    @PostMapping("/jobs")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Enqueue async backtest replay job")
    public ApiResponse<UUID> enqueueJob(
            @AuthenticationPrincipal StokrUserDetails user,
            @Valid @RequestBody ExecutionRequestDto request
    ) {
        UUID jobId = backtestJobEnqueueService.enqueueReplayJob(user.getId(), request);
        return ApiResponse.ok(jobId, CorrelationIdHolder.get());
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Cancel queued or running async backtest job (cooperative stop for RUNNING)")
    public ApiResponse<Void> cancelJob(
            @AuthenticationPrincipal StokrUserDetails user,
            @PathVariable("jobId") UUID jobId
    ) {
        backtestJobEnqueueService.cancelJob(jobId, user.getId());
        return ApiResponse.ok(CorrelationIdHolder.get());
    }

    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Backtest job status")
    public ApiResponse<BacktestJobStatusDto> jobStatus(
            @AuthenticationPrincipal StokrUserDetails user,
            @PathVariable("jobId") UUID jobId
    ) {
        return ApiResponse.ok(backtestJobEnqueueService.statusForUser(jobId, user.getId()), CorrelationIdHolder.get());
    }

    @PostMapping("/replay")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Deterministic synchronous replay (unified execution envelope)")
    public ApiResponse<BacktestReplayOutcome> replay(
            @AuthenticationPrincipal StokrUserDetails user,
            @Valid @RequestBody ExecutionRequestDto request
    ) {
        long seed = request.seed() != null ? request.seed() : ThreadLocalRandom.current().nextLong();
        ExecutionRequestDto resolved = request.withResolvedSeed(seed);
        StrategyMetadataResponseDto meta = strategyExecutionRequestValidator.validateAndLoadMetadata(resolved);
        StrategyDefinition def = strategyDefinitionRepository
                .findByStrategyKeyAndDeletedFalse(resolved.strategyKey())
                .orElseThrow(() -> new NotFoundException("Strategy definition not found"));
        log.info(
                "backtest.execution.accepted strategyKey={} userId={} metadataSchemaVersion={} strategyDefinitionVersion={} correlationId={}",
                resolved.strategyKey(),
                user.getId(),
                meta.schemaVersion(),
                def.getVersion(),
                CorrelationIdHolder.get()
        );
        BacktestReplayOutcome outcome = meanReversionReplayService.runReplay(
                resolved,
                user.getId(),
                meta.schemaVersion(),
                def.getVersion()
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
    public ApiResponse<BacktestReplayOutcome> replayLegacy(
            @AuthenticationPrincipal StokrUserDetails user,
            @Valid @RequestBody ExecutionRequestDto request) {
        return replay(user, request);
    }

    /** @deprecated use POST /runs/{runId}/resume */
    @PostMapping("/mean-reversion/resume/{runId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BacktestReplayOutcome> resumeLegacy(@PathVariable("runId") UUID runId) {
        return resume(runId);
    }
}
