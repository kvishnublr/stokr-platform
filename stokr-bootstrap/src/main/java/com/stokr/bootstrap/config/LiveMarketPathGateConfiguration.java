package com.stokr.bootstrap.config;

import com.stokr.common.market.LiveMarketPathOperationalGate;
import com.stokr.user.broker.PlatformFeedOperationalEvaluator;
import com.stokr.user.broker.PlatformMarketFeedService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Map;

@Configuration
public class LiveMarketPathGateConfiguration {

    @Bean
    public LiveMarketPathOperationalGate liveMarketPathOperationalGate(PlatformMarketFeedService platformMarketFeedService) {
        return (Instant now) -> {
            Map<String, Object> snap = platformMarketFeedService.infrastructureSnapshot();
            return PlatformFeedOperationalEvaluator.assessZerodhaPlatform(snap, now);
        };
    }
}
