package com.stokr.risk.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RiskMetricsService {

    private final Counter riskRejections;

    public RiskMetricsService(MeterRegistry registry) {
        this.riskRejections = Counter.builder("stokr.risk.rejections")
                .description("Synchronous risk engine rejections")
                .register(registry);
    }

    public void recordRejection() {
        riskRejections.increment();
    }
}
