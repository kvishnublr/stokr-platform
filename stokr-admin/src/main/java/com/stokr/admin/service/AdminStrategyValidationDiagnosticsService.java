package com.stokr.admin.service;

import com.stokr.execution.capital.StrategyCapitalReservationRepository;
import com.stokr.execution.capital.StrategyCapitalStateService;
import com.stokr.execution.sizing.PositionSizingTelemetryRepository;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategyExecutionConfigRepository;
import com.stokr.strategy.validation.StrategyValidationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminStrategyValidationDiagnosticsService {

    private final StrategyDefinitionRepository definitionRepository;
    private final StrategyExecutionConfigRepository configRepository;
    private final StrategyCapitalStateService capitalStateService;
    private final StrategyCapitalReservationRepository reservationRepository;
    private final PositionSizingTelemetryRepository sizingTelemetryRepository;

    public Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> strategies = new ArrayList<>();
        for (StrategyDefinition def : definitionRepository.findAllByDeletedFalse(Pageable.unpaged()).getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategyKey", def.getStrategyKey());
            row.put("validationStatus", def.getValidationStatus());
            row.put("liveShadowEnabled", def.isLiveShadowEnabled());
            row.put("enabled", def.isEnabled());
            configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(def.getStrategyKey())
                    .ifPresent(cfg -> row.put("executionConfig", configSnapshot(cfg)));
            try {
                row.put("capitalState", capitalSnapshot(def.getStrategyKey()));
            } catch (Exception ex) {
                row.put("capitalState", Map.of("error", ex.getMessage()));
            }
            row.put("activeReservations", reservationRepository.countByStrategyKeyAndStatusAndDeletedFalse(
                    def.getStrategyKey(), "ACTIVE"));
            strategies.add(row);
        }
        out.put("strategies", strategies);
        out.put("promotionPath", List.of(
                "RESEARCH", "DRY_RUN", "PAPER_VALIDATING", "LIVE_SHADOW", "LIVE_CANDIDATE", "LIVE_VALIDATED"));
        out.put("policy", Map.of(
                "paperAndLiveEligible", List.of("GAP_FILL"),
                "paperValidatingOnly", List.of("NSE_SPIKE_DETECTION", "VWAP_BOUNCE"),
                "dryRunOrDisabled", "all others"));
        out.put("reconciliationEndpoint", "/api/admin/trade-reconciliation/diagnostics");
        return out;
    }

    private Map<String, Object> configSnapshot(StrategyExecutionConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("executionMode", cfg.getExecutionMode());
        m.put("sizingMode", cfg.getSizingMode());
        m.put("forceFixedQty", cfg.isForceFixedQty());
        m.put("fixedQty", cfg.getFixedQty());
        m.put("allocatedCapital", cfg.getAllocatedCapital());
        m.put("maxPositions", cfg.getMaxPositions());
        m.put("maxCapitalPerTrade", cfg.getMaxCapitalPerTrade());
        m.put("maxTotalExposure", cfg.getMaxTotalExposure());
        m.put("capitalUtilizationMode", cfg.getCapitalUtilizationMode());
        m.put("liveEnabled", cfg.isLiveEnabled());
        m.put("validationAllowsLive", StrategyValidationStatus.parse(
                definitionRepository.findByStrategyKeyAndDeletedFalse(cfg.getStrategyKey())
                        .map(StrategyDefinition::getValidationStatus).orElse("DRY_RUN")).allowsLiveShadow());
        return m;
    }

    private Map<String, Object> capitalSnapshot(String strategyKey) {
        var snap = capitalStateService.snapshot(strategyKey, null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allocatedCapital", snap.allocatedCapital());
        m.put("deployedCapital", snap.deployedCapital());
        m.put("reservedCapital", snap.reservedCapital());
        m.put("pendingOrderCapital", snap.pendingOrderCapital());
        m.put("availableCapital", snap.availableCapital());
        m.put("utilizationPct", snap.utilizationPct());
        m.put("openPositions", snap.openPositions());
        m.put("maxPositions", snap.maxPositions());
        m.put("unrealizedPnl", snap.unrealizedPnl());
        m.put("realizedPnl", snap.realizedPnl());
        return m;
    }
}
