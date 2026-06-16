package com.stokr.strategy.dto.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Single configurable field for a strategy. Deserialized from {@code strategy_definitions.parameter_metadata_json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StrategyParameterFieldDto(
        String id,
        String type,
        String label,
        String description,
        boolean required,
        @JsonProperty("defaultValue") JsonNode defaultValue,
        JsonNode validation,
        List<String> enumValues,
        String group,
        /** Optional decimals for number fields (HTML step / display). */
        Integer precision,
        /** When set, field is shown only if strategyParameters[parameterId] equals one of values (JSON compared as text). */
        JsonNode visibleWhen
) {
}
