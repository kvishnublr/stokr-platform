package com.stokr.risk.rules;

import com.stokr.risk.api.RiskRule;
import com.stokr.common.market.MarketSegmentUtil;
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

    @Value("${stokr.risk.trading-window-mcx-start:09:00}")
    private String mcxStartStr;

    @Value("${stokr.risk.trading-window-mcx-end:23:55}")
    private String mcxEndStr;

    private LocalTime start;
    private LocalTime end;
    private LocalTime mcxStart;
    private LocalTime mcxEnd;

    @PostConstruct
    void init() {
        this.start = LocalTime.parse(startStr);
        this.end = LocalTime.parse(endStr);
        this.mcxStart = LocalTime.parse(mcxStartStr);
        this.mcxEnd = LocalTime.parse(mcxEndStr);
    }

    @Override
    public String code() {
        return "TRADING_WINDOW";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        // PAPER orders can trade outside market hours — only enforce window for LIVE
        if (context.order() != null && context.order().getExecutionMode() != null
                && "PAPER".equalsIgnoreCase(context.order().getExecutionMode().name())) {
            return RiskDecision.ok();
        }
        LocalTime t = context.nowLocal();
        boolean mcx = context.order() != null
                && MarketSegmentUtil.isMcxContext(context.order().getSymbol(), context.order().getStrategyKey());
        LocalTime windowStart = mcx ? mcxStart : start;
        LocalTime windowEnd = mcx ? mcxEnd : end;
        if (t.isBefore(windowStart) || t.isAfter(windowEnd)) {
            return RiskDecision.reject(code(), "Outside configured trading window");
        }
        return RiskDecision.ok();
    }
}
