package com.stokr.common.simulation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SimulationModeProperties.class)
public class SimulationModeConfiguration {
}
