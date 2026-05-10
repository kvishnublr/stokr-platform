package com.stokr.risk.rules;

import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
@Order(60)
@RequiredArgsConstructor
public class MaxTradesPerDayRule implements RiskRule {

    private final OmsOrderRepository omsOrderRepository;

    @Value("${stokr.risk.max-trades-per-day:200}")
    private int maxTradesPerDay;

    @Override
    public String code() {
        return "MAX_TRADES_DAY";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        ZoneId zone = context.zoneId();
        Instant evalInstant = Instant.ofEpochMilli(context.lastOrderEpochMs());
        LocalDate day = ZonedDateTime.ofInstant(evalInstant, zone).toLocalDate();
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        long count = omsOrderRepository.countByUserAndDay(context.userId(), start, end);
        if (count >= maxTradesPerDay) {
            return RiskDecision.reject(code(), "Max trades per day exceeded");
        }
        return RiskDecision.ok();
    }
}
