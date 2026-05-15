package com.stokr.bootstrap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlatformZerodhaFeedProperties.class)
public class BootstrapPlatformFeedConfiguration {
}
