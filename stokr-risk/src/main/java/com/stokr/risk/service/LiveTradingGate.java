package com.stokr.risk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LiveTradingGate {

    private final LiveTradingArmingService armingService;

    @Value("${stokr.execution.live-trading-enabled:false}")
    private boolean liveTradingEnabled;

    public boolean liveOrdersAllowed() {
        return liveTradingEnabled && armingService.isArmed();
    }
}
