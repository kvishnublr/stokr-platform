package com.stokr.backtest.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.backtest.service.StrategyResearchQueryService;
import com.stokr.backtest.web.dto.StrategyLeaderboardRowDto;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/backtest/research")
@RequiredArgsConstructor
@Tag(name = "Strategy research")
public class StrategyResearchController {

    private final StrategyResearchQueryService strategyResearchQueryService;

    @GetMapping("/leaderboard")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<StrategyLeaderboardRowDto>> leaderboard(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(strategyResearchQueryService.leaderboard(user.getId()), CorrelationIdHolder.get());
    }
}
