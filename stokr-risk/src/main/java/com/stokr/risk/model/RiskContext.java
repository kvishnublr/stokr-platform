package com.stokr.risk.model;

import com.stokr.oms.domain.OmsOrder;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

public record RiskContext(
        UUID userId,
        OmsOrder order,
        BigDecimal dayPnl,
        int openPositionCount,
        LocalTime nowLocal,
        ZoneId zoneId,
        long lastOrderEpochMs
) {
}
