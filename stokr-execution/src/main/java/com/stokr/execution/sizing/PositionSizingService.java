package com.stokr.execution.sizing;

import com.stokr.execution.capital.StrategyCapitalStateService;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Single source of truth for order quantity. Never silently falls back to qty=1 on failure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PositionSizingService {

    private final StrategyExecutionConfigService strategyExecutionConfigService;
    private final StrategyCapitalStateService capitalStateService;
    private final BrokerNormalizationService brokerNormalizationService;

    /** Legacy entry — delegates to institutional sizing. */
    public BigDecimal resolveQuantity(
            String strategyKey,
            UUID userId,
            BigDecimal suggestedQty,
            BigDecimal marketPrice,
            boolean testTrade) {
        PositionSizingResult result = resolve(PositionSizingRequest.ofLegacy(
                strategyKey, userId, suggestedQty, marketPrice, testTrade));
        if (!result.accepted()) {
            throw new PositionSizingRejectedException(result.rejectedReason());
        }
        return result.normalizedQuantity();
    }

    public PositionSizingResult resolve(PositionSizingRequest request) {
        Optional<StrategyExecutionConfig> cfgOpt = resolveConfig(request);

        if (cfgOpt.isEmpty()) {
            StrategyExecutionConfig provisioned =
                    strategyExecutionConfigService.ensureGlobalExecutionConfig(request.strategyKey());
            cfgOpt = Optional.of(provisioned);
        }

        StrategyExecutionConfig cfg = cfgOpt.get();
        SizingMode mode = resolveSizingMode(cfg);
        Map<String, Object> snapshot = buildSnapshot(cfg, request);
        String hash = hashSnapshot(snapshot);

        BigDecimal marketPrice = effectiveMarketPrice(request, cfg, mode);
        if (marketPrice == null) {
            return reject("STALE_OR_MISSING_PRICE", mode, request, snapshot, hash);
        }

        if (request.testTrade() && (cfg.isForceFixedQty() || mode == SizingMode.FIXED_QUANTITY)) {
            return resolveTestTradeFixedQuantity(request, cfg, mode, snapshot, hash, marketPrice);
        }

        StrategyCapitalStateService.StrategyCapitalSnapshot capital =
                capitalStateService.snapshot(request.strategyKey(), request.userId());
        snapshot.put("availableCapital", capital.availableCapital());
        snapshot.put("capitalAllocated", capital.allocatedCapital());
        snapshot.put("utilizationPct", capital.utilizationPct());

        if (capital.openPositions() >= cfg.getMaxPositions()) {
            return reject("MAX_POSITIONS_EXCEEDED", mode, request, snapshot, hash);
        }

        BigDecimal rawQty = computeRawQuantity(cfg, mode, capital, marketPrice);
        if (rawQty == null || rawQty.compareTo(BigDecimal.ZERO) <= 0) {
            return reject("INVALID_COMPUTED_QUANTITY", mode, request, snapshot, hash);
        }

        if (cfg.getMaxTradeQuantity() != null && rawQty.compareTo(cfg.getMaxTradeQuantity()) > 0) {
            rawQty = cfg.getMaxTradeQuantity();
        }

        BigDecimal exposure = rawQty.multiply(marketPrice);

        boolean capitalEnforced = cfg.getAllocatedCapital() != null
                && cfg.getAllocatedCapital().compareTo(BigDecimal.ZERO) > 0
                && mode != SizingMode.FIXED_QUANTITY;

        if (capitalEnforced && cfg.getMaxCapitalPerTrade() != null && exposure.compareTo(cfg.getMaxCapitalPerTrade()) > 0) {
            rawQty = cfg.getMaxCapitalPerTrade()
                    .divide(marketPrice, 0, RoundingMode.FLOOR);
            exposure = rawQty.multiply(marketPrice);
        }

        if (capitalEnforced && capital.availableCapital().compareTo(exposure) < 0) {
            return reject("INSUFFICIENT_STRATEGY_CAPITAL", mode, request, snapshot, hash);
        }

        if (capitalEnforced && cfg.getMaxTotalExposure() != null) {
            BigDecimal total = capital.deployedCapital().add(capital.reservedCapital())
                    .add(capital.pendingOrderCapital()).add(exposure);
            if (total.compareTo(cfg.getMaxTotalExposure()) > 0) {
                return reject("MAX_TOTAL_EXPOSURE", mode, request, snapshot, hash);
            }
        }

        BrokerNormalizationService.NormalizationResult norm =
                brokerNormalizationService.normalize(request.symbol(), rawQty);
        if (!norm.accepted()) {
            return reject("BROKER_NORMALIZATION:" + norm.note(), mode, request, snapshot, hash);
        }

        BigDecimal availAfter = capital.availableCapital().subtract(exposure);
        BigDecimal utilization = capital.allocatedCapital().compareTo(BigDecimal.ZERO) > 0
                ? capital.allocatedCapital().subtract(availAfter)
                        .divide(capital.allocatedCapital(), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        return PositionSizingResult.accepted(
                rawQty,
                norm.normalizedQuantity(),
                exposure,
                exposure,
                capital.availableCapital(),
                availAfter.max(BigDecimal.ZERO),
                utilization,
                mode,
                snapshot,
                hash,
                norm.note());
    }

    private Optional<StrategyExecutionConfig> resolveConfig(PositionSizingRequest request) {
        if (request.userId() != null) {
            return strategyExecutionConfigService.getByStrategyKeyForUser(request.userId(), request.strategyKey());
        }
        return strategyExecutionConfigService.getByStrategyKey(request.strategyKey());
    }

    private BigDecimal effectiveMarketPrice(PositionSizingRequest request, StrategyExecutionConfig cfg, SizingMode mode) {
        if (request.marketPrice() != null && request.marketPrice().compareTo(BigDecimal.ZERO) > 0) {
            return request.marketPrice();
        }
        if (request.testTrade() && (cfg.isForceFixedQty() || mode == SizingMode.FIXED_QUANTITY)) {
            return BigDecimal.ONE;
        }
        return null;
    }

    private PositionSizingResult resolveTestTradeFixedQuantity(
            PositionSizingRequest request,
            StrategyExecutionConfig cfg,
            SizingMode mode,
            Map<String, Object> snapshot,
            String hash,
            BigDecimal marketPrice) {
        BigDecimal rawQty = request.suggestedQty() != null && request.suggestedQty().signum() > 0
                ? request.suggestedQty()
                : (cfg.getFixedQty() != null ? cfg.getFixedQty() : BigDecimal.ONE);
        if (cfg.getMaxTradeQuantity() != null && rawQty.compareTo(cfg.getMaxTradeQuantity()) > 0) {
            rawQty = cfg.getMaxTradeQuantity();
        }
        BrokerNormalizationService.NormalizationResult norm =
                brokerNormalizationService.normalize(request.symbol(), rawQty);
        if (!norm.accepted()) {
            return reject("BROKER_NORMALIZATION:" + norm.note(), mode, request, snapshot, hash);
        }
        BigDecimal exposure = norm.normalizedQuantity().multiply(marketPrice);
        snapshot.put("testTradeFastPath", true);
        return PositionSizingResult.accepted(
                rawQty,
                norm.normalizedQuantity(),
                exposure,
                exposure,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                mode,
                snapshot,
                hash,
                norm.note());
    }

    private BigDecimal computeRawQuantity(
            StrategyExecutionConfig cfg,
            SizingMode mode,
            StrategyCapitalStateService.StrategyCapitalSnapshot capital,
            BigDecimal price) {
        if (cfg.isForceFixedQty() || mode == SizingMode.FIXED_QUANTITY) {
            return cfg.getFixedQty() != null ? cfg.getFixedQty() : BigDecimal.ONE;
        }
        return switch (mode) {
            case FIXED_CAPITAL_PER_TRADE -> {
                BigDecimal cap = cfg.getMaxCapitalPerTrade();
                if (cap == null || cap.compareTo(BigDecimal.ZERO) <= 0) {
                    yield null;
                }
                yield cap.divide(price, 0, RoundingMode.FLOOR).max(BigDecimal.ONE);
            }
            case CAPITAL_BUCKET -> {
                BigDecimal perTrade = cfg.getMaxCapitalPerTrade();
                if (perTrade == null && cfg.getAllocatedCapital() != null && cfg.getMaxPositions() > 0) {
                    perTrade = cfg.getAllocatedCapital()
                            .divide(BigDecimal.valueOf(cfg.getMaxPositions()), 8, RoundingMode.FLOOR);
                }
                if (perTrade == null || perTrade.compareTo(BigDecimal.ZERO) <= 0) {
                    yield null;
                }
                yield perTrade.divide(price, 0, RoundingMode.FLOOR).max(BigDecimal.ONE);
            }
            case RISK_BASED -> {
                if (cfg.getMaxRiskPerTradePct() == null || cfg.getAllocatedCapital() == null) {
                    yield null;
                }
                BigDecimal riskBudget = cfg.getAllocatedCapital()
                        .multiply(cfg.getMaxRiskPerTradePct())
                        .divide(BigDecimal.valueOf(100), 8, RoundingMode.FLOOR);
                yield riskBudget.divide(price, 0, RoundingMode.FLOOR).max(BigDecimal.ONE);
            }
            default -> cfg.getFixedQty() != null ? cfg.getFixedQty() : BigDecimal.ONE;
        };
    }

    private SizingMode resolveSizingMode(StrategyExecutionConfig cfg) {
        if (cfg.getSizingMode() != null && !cfg.getSizingMode().isBlank()) {
            return SizingMode.parse(cfg.getSizingMode());
        }
        if (cfg.isForceFixedQty()) {
            return SizingMode.FIXED_QUANTITY;
        }
        if (cfg.getAllocatedCapital() != null && cfg.getMaxPositions() > 0) {
            return SizingMode.CAPITAL_BUCKET;
        }
        return SizingMode.FIXED_QUANTITY;
    }

    private Map<String, Object> buildSnapshot(StrategyExecutionConfig cfg, PositionSizingRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategyKey", request.strategyKey());
        m.put("sizingMode", resolveSizingMode(cfg).name());
        m.put("capitalAllocated", cfg.getAllocatedCapital());
        m.put("maxPositions", cfg.getMaxPositions());
        m.put("maxCapitalPerTrade", cfg.getMaxCapitalPerTrade());
        m.put("maxTotalExposure", cfg.getMaxTotalExposure());
        m.put("capitalUtilizationMode", cfg.getCapitalUtilizationMode());
        m.put("executionMode", request.executionMode() != null ? request.executionMode().name() : null);
        m.put("entryPrice", request.marketPrice());
        m.put("collectedAt", Instant.now().toString());
        m.put("configVersion", cfg.getVersion());
        return m;
    }

    private static String hashSnapshot(Map<String, Object> snapshot) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(String.valueOf(snapshot).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception ex) {
            return "unknown";
        }
    }

    private PositionSizingResult reject(String reason, SizingMode mode, PositionSizingRequest req, Map<String, Object> snap) {
        return reject(reason, mode, req, snap, snap != null ? hashSnapshot(snap) : null);
    }

    private PositionSizingResult reject(
            String reason, SizingMode mode, PositionSizingRequest req,
            Map<String, Object> snap, String hash) {
        log.error("sizing.REJECTED strategy={} signal={} reason={}", req.strategyKey(), req.signalId(), reason);
        return PositionSizingResult.rejected(reason, mode, snap != null ? snap : Map.of(), hash);
    }
}
