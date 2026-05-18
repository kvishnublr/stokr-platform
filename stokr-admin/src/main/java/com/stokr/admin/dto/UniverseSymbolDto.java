package com.stokr.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record UniverseSymbolDto(
        UUID id,
        UUID groupId,
        String symbol,
        String tradingSymbol,
        String underlyingSymbol,
        String exchange,
        String instrumentType,
        Long instrumentToken,
        Integer lotSize,
        BigDecimal tickSize,
        LocalDate expiry,
        BigDecimal strike,
        String optionType,
        boolean enabled,
        Instant createdAt
) {}
