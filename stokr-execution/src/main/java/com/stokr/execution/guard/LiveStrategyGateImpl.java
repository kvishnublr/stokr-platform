package com.stokr.execution.guard;

import com.stokr.common.exception.BadRequestException;
import com.stokr.common.trading.LiveStrategyGate;
import com.stokr.risk.model.LiveTraderEligibilityResult;
import com.stokr.risk.service.LiveTradingTraderEligibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LiveStrategyGateImpl implements LiveStrategyGate {

    private final LiveTradingTraderEligibilityService traderEligibilityService;

    @Override
    public void assertLiveRuntimeAllowed(UUID userId, String strategyKey) {
        LiveTraderEligibilityResult r =
                traderEligibilityService.evaluateForLiveStrategyActivation(userId, strategyKey, "ZERODHA");
        if (!r.allowed()) {
            throw new BadRequestException(r.message());
        }
    }
}
