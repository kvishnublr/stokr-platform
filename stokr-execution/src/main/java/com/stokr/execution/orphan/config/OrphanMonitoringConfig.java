package com.stokr.execution.orphan.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "stokr.orphan.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class OrphanMonitoringConfig {
}
