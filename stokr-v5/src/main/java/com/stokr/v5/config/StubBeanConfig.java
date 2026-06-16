package com.stokr.v5.config;

import com.stokr.common.notification.whatsapp.WhatsAppProvider;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StubBeanConfig {

    private static final Logger log = LoggerFactory.getLogger(StubBeanConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public WhatsAppProvider whatsAppProvider() {
        log.warn("Using no-op WhatsAppProvider — configure a real provider for WhatsApp features");
        return new WhatsAppProvider() {
            @Override public String providerKey() { return "noop"; }
            @Override public void sendVerificationOtp(String e164, String otpCode, Instant expiresAt) {
                log.warn("no-op WhatsAppProvider.sendVerificationOtp e164={} otp={}", e164, otpCode);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        log.info("Creating RabbitAdmin bean");
        return new RabbitAdmin(connectionFactory);
    }
}
