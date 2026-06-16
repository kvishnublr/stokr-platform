package com.stokr.strategy.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Trader-facing capital allocation document. Used both as the GET response and
 * the PUT request body. Each allocation maps to a set of member strategies whose
 * per-strategy execution configs carry the actual sizing fields.
 */
public record TraderCapitalAllocationView(
        BigDecimal totalCapital,
        List<AssetClassAllocationDto> allocations
) {}
