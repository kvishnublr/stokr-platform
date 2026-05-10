package com.stokr.user.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ZerodhaBrokerProperties.class, TelegramBotProperties.class})
public class UserModuleConfiguration {
}
