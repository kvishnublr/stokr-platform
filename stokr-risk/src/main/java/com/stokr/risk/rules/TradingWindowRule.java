package com.stokr.risk.rules;

import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

@Component
@Order(20)
public class TradingWindowRule implements RiskRule {

    @Value("${stokr.risk.trading-window-start:09:25}")
    private String startStr;

    @Value("${stokr.risk.trading-window-end:14:45}")
    private String endStr;

    private LocalTime start;
    private LocalTime end;

    @PostConstruct
    void init() {
        this.start = LocalTime.parse(startStr);
        this.end = LocalTime.parse(endStr);
    }

    @Override
    public String code() {
        return "TRADING_WINDOW";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        LocalTime t = context.nowLocal();
        if (t.isBefore(start) || t.isAfter(end)) {
            return RiskDecision.reject(code(), "Outside configured trading window");
        }
        return RiskDecision.ok();
    }
}
