package com.stokr.backtest.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.backtest.web.dto.ExecutionRequestDto;
import com.stokr.backtest.web.dto.TimeRangeDto;
import com.stokr.common.exception.BadRequestException;
import com.stokr.marketdata.service.MarketDataCoverageService;
import com.stokr.strategy.dto.metadata.StrategyMetadataResponseDto;
import com.stokr.strategy.dto.metadata.StrategyParameterFieldDto;
import com.stokr.strategy.service.StrategyMetadataQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Metadata-aware validation for {@link ExecutionRequestDto}. No silent coercion.
 */
@Service
@RequiredArgsConstructor
public class StrategyExecutionRequestValidator {

    private static final Set<String> RESERVED_PARAMETER_KEYS = Set.of(
            "symbol", "timeframe", "capital", "executionmode", "executionprofile", "feemodel", "slippagemodel", "seed", "range"
    );
    private static final Set<String> SUPPORTED_BACKTEST_STRATEGY_KEYS = Set.of(
            "NSE_SPIKE_DETECTION",
            "GAP_FILL",
            "VWAP_BOUNCE",
            "SECTOR_LAGGARD",
            "EARLY_BREAKOUT",
            "ADV_CASH",
            "NIFTY_CATCHUP",
            "VWAP_SQUEEZE",
            "VWAP_CLOSE_RECLAIM",
            "OPENING_RANGE_BREAKOUT"
    );

    private final StrategyMetadataQueryService strategyMetadataQueryService;
    private final MarketDataCoverageService marketDataCoverageService;

    public StrategyMetadataResponseDto validateAndLoadMetadata(ExecutionRequestDto req) {
        if (req.strategyKey() == null || req.strategyKey().isBlank()) {
            throw new BadRequestException("strategyKey is required");
        }
        String strategyKey = req.strategyKey().trim();
        if (!SUPPORTED_BACKTEST_STRATEGY_KEYS.contains(strategyKey)) {
            throw new BadRequestException("Unsupported strategyKey for backtest: " + strategyKey);
        }
        if (!"BACKTEST".equalsIgnoreCase(req.executionMode())) {
            throw new BadRequestException("Synchronous replay requires executionMode=BACKTEST");
        }
        TimeRangeDto range = req.range();
        if (!range.from().isBefore(range.to())) {
            throw new BadRequestException("range.from must be strictly before range.to");
        }
        for (String k : req.strategyParameters().keySet()) {
            if (k == null || k.isBlank()) {
                throw new BadRequestException("strategyParameters contains a blank key");
            }
            if (RESERVED_PARAMETER_KEYS.contains(k.trim().toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("strategyParameters must not use reserved key: " + k);
            }
        }

        StrategyMetadataResponseDto meta = strategyMetadataQueryService.byStrategyKey(req.strategyKey());
        if (!meta.executionCapabilities().backtest()) {
            throw new BadRequestException("Strategy does not allow BACKTEST execution");
        }
        assertInList(req.timeframe(), meta.allowedTimeframes(), "timeframe");
        assertInList(req.executionMode().toUpperCase(Locale.ROOT), meta.allowedExecutionModes(), "executionMode");
        assertInList(req.feeModel(), meta.allowedFeeModels(), "feeModel");
        assertInList(req.slippageModel(), meta.allowedSlippageModels(), "slippageModel");
        assertInList(req.executionProfile(), meta.allowedExecutionProfiles(), "executionProfile");

        for (StrategyParameterFieldDto p : meta.parameters()) {
            if (!isVisible(p, req.strategyParameters())) {
                continue;
            }
            Object raw = req.strategyParameters().get(p.id());
            if (raw == null) {
                if (p.required()) {
                    throw new BadRequestException("Missing strategy parameter: " + p.id());
                }
                continue;
            }
            validateField(p, raw);
        }
        validateCoverage(req);
        return meta;
    }

    private void validateCoverage(ExecutionRequestDto req) {
        var assessment = marketDataCoverageService.assessReadiness(
                req.symbol(),
                req.timeframe(),
                req.range().from(),
                req.range().to(),
                "REPLAY",
                true
        );
        if (assessment.ready()) {
            return;
        }
        // Backtest replay tolerates intraday gaps ??? missing ticks are handled by the replay engine.
        // Only hard-block when there is no data at all for the requested window.
        String state = assessment.state() == null ? "" : assessment.state();
        if ("GAPS_PRESENT".equalsIgnoreCase(state)) {
            return;
        }
        String detail = assessment.detail() == null ? "" : assessment.detail();
        if ("INCOMPLETE_RANGE".equalsIgnoreCase(state)
                && assessment.coverageStart() != null
                && assessment.coverageEnd() != null) {
            detail = detail + " (available "
                    + assessment.coverageStart()
                    + " to "
                    + assessment.coverageEnd()
                    + (assessment.latestCandleAt() != null ? ", latest bar " + assessment.latestCandleAt() : "")
                    + ")";
        }
        throw new BadRequestException(state + ": " + detail);
    }

    private static void assertInList(String value, List<String> allowed, String field) {
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        boolean ok = allowed.stream().anyMatch(v -> v.equalsIgnoreCase(value.trim()));
        if (!ok) {
            throw new BadRequestException(field + " has unsupported value: " + value);
        }
    }

    private static boolean isVisible(StrategyParameterFieldDto p, Map<String, Object> strategyParameters) {
        JsonNode when = p.visibleWhen();
        if (when == null || when.isNull() || when.isMissingNode()) {
            return true;
        }
        String pid = when.path("parameterId").asText(null);
        if (pid == null || pid.isBlank()) {
            return true;
        }
        JsonNode expect = when.get("equals");
        Object actual = strategyParameters.get(pid);
        if (expect == null || expect.isNull()) {
            return true;
        }
        return jsonEquals(expect, actual);
    }

    private static boolean jsonEquals(JsonNode expect, Object actual) {
        if (expect.isBoolean()) {
            return expect.asBoolean() == toBoolean(actual);
        }
        if (expect.isTextual()) {
            return expect.asText().equals(String.valueOf(actual));
        }
        if (expect.isNumber()) {
            return expect.decimalValue().compareTo(toBigDecimal(actual)) == 0;
        }
        return false;
    }

    private static void validateField(StrategyParameterFieldDto p, Object raw) {
        switch (p.type()) {
            case "boolean" -> {
                toBoolean(raw); // throws if invalid
            }
            case "integer" -> {
                int v = toIntExact(raw, p.id());
                JsonNode val = p.validation();
                if (val != null && !val.isNull()) {
                    if (val.has("min") && v < val.get("min").asInt()) {
                        throw new BadRequestException(p.id() + " below min");
                    }
                    if (val.has("max") && v > val.get("max").asInt()) {
                        throw new BadRequestException(p.id() + " above max");
                    }
                }
            }
            case "number" -> {
                BigDecimal v = toBigDecimal(raw);
                JsonNode val = p.validation();
                if (val != null && !val.isNull()) {
                    if (val.has("min") && v.compareTo(val.get("min").decimalValue()) < 0) {
                        throw new BadRequestException(p.id() + " below min");
                    }
                    if (val.has("max") && v.compareTo(val.get("max").decimalValue()) > 0) {
                        throw new BadRequestException(p.id() + " above max");
                    }
                }
            }
            case "enum" -> {
                String s = String.valueOf(raw).trim();
                if (p.enumValues() == null || p.enumValues().stream().noneMatch(v -> v.equals(s))) {
                    throw new BadRequestException(p.id() + " must be one of " + p.enumValues());
                }
            }
            case "string" -> {
                String s = String.valueOf(raw);
                JsonNode val = p.validation();
                if (val != null && val.has("maxLength") && s.length() > val.get("maxLength").asInt()) {
                    throw new BadRequestException(p.id() + " exceeds maxLength");
                }
            }
            default -> throw new BadRequestException("Unsupported parameter type for " + p.id() + ": " + p.type());
        }
    }

    private static boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        throw new BadRequestException("Parameter must be boolean");
    }

    private static int toIntExact(Object raw, String id) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (Math.floor(d) != d) {
                throw new BadRequestException(id + " must be a whole number");
            }
            if (d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) {
                throw new BadRequestException(id + " out of int range");
            }
            return (int) d;
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException(id + " must be integer");
            }
        }
        throw new BadRequestException(id + " must be integer");
    }

    private static BigDecimal toBigDecimal(Object raw) {
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        if (raw instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (raw instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("Invalid number");
            }
        }
        throw new BadRequestException("Invalid number");
    }
}
