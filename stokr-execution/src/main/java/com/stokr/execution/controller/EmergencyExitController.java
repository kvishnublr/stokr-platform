package com.stokr.execution.controller;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.execution.service.PositionExitOrchestratorService;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalOwnerType;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Emergency endpoint to force-close all positions immediately
 * Used when market close auto-exit fails or positions need immediate closure
 */
@RestController
@RequestMapping("/api/emergency")
@RequiredArgsConstructor
@Slf4j
public class EmergencyExitController {

    private final PortfolioPositionRepository positionRepository;
    private final StrategySignalRepository signalRepository;
    private final PositionExitOrchestratorService positionExitOrchestratorService;
    private static final UUID PRIMARY_TRADER_ID = UUID.fromString("6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4");

    @PostMapping("/force-close-all-positions")
    @Transactional
    public ApiResponse<Map<String, Object>> forceCloseAllPositions() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Get all open positions
            var positions = positionRepository.findByUserIdAndDeletedFalse(PRIMARY_TRADER_ID);
            int closedCount = 0;

            for (var position : positions) {
                if (position.getQuantity().compareTo(java.math.BigDecimal.ZERO) != 0) {
                    // Generate EXIT signal for each position
                    StrategySignalEntity exitSignal = new StrategySignalEntity();
                    exitSignal.setUserId(PRIMARY_TRADER_ID);
                    exitSignal.setSignalType(SignalType.EXIT);
                    exitSignal.setSymbol(position.getSymbol());
                    exitSignal.setSuggestedQty(position.getQuantity().abs());
                    exitSignal.setReason("EMERGENCY FORCE CLOSE - Manual override");
                    exitSignal.setStrategyName("EMERGENCY_EXIT");
                    exitSignal.setStrategyVersion("1.0");
                    exitSignal.setPipeline("EMERGENCY");
                    exitSignal.setOwnerType(SignalOwnerType.SYSTEM);
                    exitSignal.setSignalSource(SignalProvenance.LIVE);
                    exitSignal.setTestTrade(false);
                    exitSignal.setSimulation(false);

                    signalRepository.save(exitSignal);
                    var exitResult = positionExitOrchestratorService.flattenSymbol(
                            PRIMARY_TRADER_ID,
                            position.getSymbol(),
                            "EMERGENCY_EXIT",
                            exitSignal.getReason()
                    );
                    closedCount++;

                    log.error("emergency.force_close symbol={} qty={} signalId={} ordersCreated={} notes={}",
                            position.getSymbol(), position.getQuantity(), exitSignal.getId(),
                            exitResult.get("ordersCreated"), exitResult.get("notes"));
                }
            }

            result.put("status", "SUCCESS");
            result.put("positions_closed", closedCount);
            result.put("timestamp", Instant.now().toString());
            result.put("reason", "Emergency force close - past market close time");

            log.error("emergency.force_close_all completed closedCount={}", closedCount);

            return ApiResponse.ok(result, CorrelationIdHolder.get());
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            log.error("emergency.force_close_all_failed error={}", e.getMessage(), e);
            return ApiResponse.ok(result, CorrelationIdHolder.get());
        }
    }
}
