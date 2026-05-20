package com.stokr.admin.web;

import com.stokr.admin.dto.AdminRiskDashboardDto;
import com.stokr.admin.dto.StrategyRiskStateDto;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.reconciliation.ReconciliationEventRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.service.BrokerOperationalCircuitService;
import com.stokr.risk.service.KillSwitchService;
import com.stokr.risk.service.LiveTradingArmingService;
import com.stokr.strategy.capital.StrategyCapitalManager;
import com.stokr.strategy.capital.StrategyCapitalSummary;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.repository.StrategyExecutionConfigRepository;
import com.stokr.strategy.service.StrategyDailyLossTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin/risk-dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Risk Dashboard")
public class AdminRiskDashboardController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final KillSwitchService killSwitchService;
    private final BrokerOperationalCircuitService brokerOperationalCircuitService;
    private final LiveTradingArmingService liveTradingArmingService;
    private final OmsOrderRepository omsOrderRepository;
    private final ReconciliationEventRepository reconciliationEventRepository;
    private final StrategyExecutionConfigRepository strategyExecutionConfigRepository;
    private final StrategyDailyLossTrackerService dailyLossTrackerService;
    private final StrategyCapitalManager strategyCapitalManager;

    @GetMapping
    @Operation(summary = "Get the risk dashboard snapshot")
    public ApiResponse<AdminRiskDashboardDto> dashboard() {
        Instant startOfDay = ZonedDateTime.now(IST).toLocalDate().atStartOfDay(IST).toInstant();
        List<Object[]> stats = omsOrderRepository.computeStats(startOfDay);
        long todayOrders = 0, todayFills = 0, todayRejects = 0;
        if (!stats.isEmpty()) {
            Object[] row = stats.get(0);
            todayOrders = toLong(row[0]);
            todayFills = toLong(row[1]);
            todayRejects = toLong(row[2]);
        }

        int openAlerts = reconciliationEventRepository
                .findAllByDeletedFalseAndStatusOrderByCreatedAtDesc("OPEN", PageRequest.of(0, 1000))
                .size();

        List<StrategyExecutionConfig> configs = strategyExecutionConfigRepository
                .findAllByDeletedFalseOrderByStrategyKeyAsc();

        List<StrategyCapitalSummary> capitalSummaries = strategyCapitalManager.globalSummary().strategies();

        List<StrategyRiskStateDto> riskStates = configs.stream().map(cfg -> {
            StrategyCapitalSummary cap = capitalSummaries.stream()
                    .filter(c -> c.strategyKey().equals(cfg.getStrategyKey()))
                    .findFirst().orElse(null);
            return new StrategyRiskStateDto(
                    cfg.getStrategyKey(),
                    cfg.isEnabled(),
                    cfg.isLiveEnabled(),
                    cfg.isEmergencyStopEnabled(),
                    cfg.isAutoDisableOnLoss(),
                    cfg.getDailyLossLimit(),
                    dailyLossTrackerService.getTodayPnl(cfg.getStrategyKey()),
                    cfg.getMaxPositions(),
                    cap != null ? cap.openPositions() : 0,
                    cfg.getAllocatedCapital(),
                    cap != null ? cap.utilizedCapital() : null,
                    cfg.getExecutionMode()
            );
        }).toList();

        AdminRiskDashboardDto dto = new AdminRiskDashboardDto(
                Instant.now(),
                killSwitchService.isEnabled(),
                brokerOperationalCircuitService.isGlobalBrokerHalt(),
                liveTradingArmingService.isArmed(),
                (int) riskStates.stream().filter(StrategyRiskStateDto::enabled).count(),
                (int) riskStates.stream().filter(StrategyRiskStateDto::liveEnabled).count(),
                (int) riskStates.stream().filter(StrategyRiskStateDto::emergencyStopEnabled).count(),
                todayOrders,
                todayFills,
                todayRejects,
                openAlerts,
                riskStates
        );
        return ApiResponse.ok(dto, CorrelationIdHolder.get());
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }
}
