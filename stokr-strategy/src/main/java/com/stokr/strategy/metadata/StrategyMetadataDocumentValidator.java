package com.stokr.strategy.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.strategy.dto.metadata.StrategyMetadataResponseDto;
import com.stokr.strategy.dto.metadata.StrategyParameterFieldDto;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates published strategy metadata documents. Used at startup and when loading metadata.
 */
public final class StrategyMetadataDocumentValidator {

    private static final Set<String> ALLOWED_TYPES = Set.of("string", "number", "integer", "enum", "boolean");

    private StrategyMetadataDocumentValidator() {
    }

    public static void validateOrThrow(StrategyMetadataResponseDto m) {
        if (m.strategyKey() == null || m.strategyKey().isBlank()) {
            throw new IllegalStateException("metadata: missing strategyKey");
        }
        if (m.schemaVersion() < 1) {
            throw new IllegalStateException("metadata: invalid schemaVersion for " + m.strategyKey());
        }
        if (m.executionCapabilities() == null) {
            throw new IllegalStateException("metadata: missing executionCapabilities for " + m.strategyKey());
        }
        List<StrategyParameterFieldDto> params = m.parameters();
        if (params == null || params.isEmpty()) {
            throw new IllegalStateException("metadata: parameters empty for " + m.strategyKey());
        }
        Set<String> ids = new HashSet<>();
        for (StrategyParameterFieldDto p : params) {
            if (p.id() == null || p.id().isBlank()) {
                throw new IllegalStateException("metadata: parameter without id for " + m.strategyKey());
            }
            if (!ids.add(p.id())) {
                throw new IllegalStateException("metadata: duplicate parameter id '" + p.id() + "' for " + m.strategyKey());
            }
            if (p.type() == null || !ALLOWED_TYPES.contains(p.type())) {
                throw new IllegalStateException("metadata: invalid type '" + p.type() + "' for param " + p.id());
            }
            if (p.label() == null || p.label().isBlank()) {
                throw new IllegalStateException("metadata: missing label for param " + p.id());
            }
            if ("enum".equals(p.type())) {
                if (p.enumValues() == null || p.enumValues().isEmpty()) {
                    throw new IllegalStateException("metadata: enum without enumValues for " + p.id());
                }
                if (p.defaultValue() != null && !p.defaultValue().isNull()) {
                    String dv = p.defaultValue().asText();
                    if (!p.enumValues().contains(dv)) {
                        throw new IllegalStateException("metadata: default not in enumValues for " + p.id());
                    }
                }
            }
            JsonNode v = p.validation();
            if (v != null && !v.isNull()) {
                if (v.has("min") && v.has("max") && v.get("min").isNumber() && v.get("max").isNumber()) {
                    BigDecimal min = v.get("min").decimalValue();
                    BigDecimal max = v.get("max").decimalValue();
                    if (min.compareTo(max) > 0) {
                        throw new IllegalStateException("metadata: min>max for " + p.id());
                    }
                }
            }
        }
        validateAllowedList("allowedTimeframes", m.allowedTimeframes());
        validateAllowedList("allowedExecutionModes", m.allowedExecutionModes());
        validateAllowedList("allowedFeeModels", m.allowedFeeModels());
        validateAllowedList("allowedSlippageModels", m.allowedSlippageModels());
        validateAllowedList("allowedExecutionProfiles", m.allowedExecutionProfiles());
    }

    private static void validateAllowedList(String name, List<String> list) {
        if (list == null) {
            return;
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("metadata: " + name + " is empty (omit or populate)");
        }
        Set<String> seen = new HashSet<>();
        for (String s : list) {
            if (s == null || s.isBlank()) {
                throw new IllegalStateException("metadata: " + name + " contains blank entry");
            }
            if (!seen.add(s.trim().toUpperCase(Locale.ROOT))) {
                throw new IllegalStateException("metadata: duplicate in " + name + ": " + s);
            }
        }
    }
}
