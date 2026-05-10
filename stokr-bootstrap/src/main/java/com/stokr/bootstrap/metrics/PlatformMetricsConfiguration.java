package com.stokr.bootstrap.metrics;

import com.stokr.strategy.repository.StrategyInstanceRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformMetricsConfiguration {

    @Bean
    public MeterBinder runningStrategiesGauge(StrategyInstanceRepository strategyInstanceRepository) {
        return registry -> Gauge.builder("stokr.strategy.runtime.running", strategyInstanceRepository,
                        r -> r.countByRuntimeStateAndDeletedFalse("RUNNING"))
                .description("Strategy instances in RUNNING state")
                .register(registry);
    }
}
