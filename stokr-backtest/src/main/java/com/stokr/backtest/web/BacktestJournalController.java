package com.stokr.backtest.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.backtest.service.BacktestJournalQueryService;
import com.stokr.backtest.web.dto.BacktestJournalEntryDto;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/backtest/runs")
@RequiredArgsConstructor
@Tag(name = "Backtest journal")
public class BacktestJournalController {

    private final BacktestJournalQueryService backtestJournalQueryService;

    @GetMapping("/{runId}/journal")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<BacktestJournalEntryDto>> journal(
            @AuthenticationPrincipal StokrUserDetails user,
            @PathVariable("runId") UUID runId
    ) {
        return ApiResponse.ok(backtestJournalQueryService.journalForRun(runId, user.getId()), CorrelationIdHolder.get());
    }
}
