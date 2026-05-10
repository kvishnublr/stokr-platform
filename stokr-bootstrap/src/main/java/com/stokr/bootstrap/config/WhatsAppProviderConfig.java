package com.stokr.bootstrap.config;

import com.stokr.bootstrap.notification.whatsapp.TwilioWhatsAppPlaceholder;
import com.stokr.common.notification.whatsapp.WhatsAppProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WhatsAppProviderConfig {

    /**
     * Explicit bean so {@link com.stokr.user.whatsapp.WhatsappVerificationService} always has a provider
     * when no real Twilio/Meta integration is on the classpath.
     */
    @Bean
    @ConditionalOnMissingBean(WhatsAppProvider.class)
    public WhatsAppProvider whatsAppProvider() {
        return new TwilioWhatsAppPlaceholder();
    }
}
