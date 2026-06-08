package com.stokr.user.broker;

import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.telegram.ZerodhaOAuthTelegramAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls platform Zerodha session and sends Telegram reconnect link when OAuth is required. */
@Component
@RequiredArgsConstructor
public class ZerodhaOAuthWatchScheduler {

    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final ZerodhaOAuthTelegramAlertService oauthTelegramAlertService;

    @Scheduled(fixedDelayString = "${stokr.broker.zerodha.oauth-alert-poll-ms:900000}")
    public void watchPlatformOAuth() {
        if (!zerodhaBrokerProperties.isOauthAlertEnabled() || !zerodhaBrokerProperties.isConfigured()) {
            return;
        }
        oauthTelegramAlertService.pollAndAlertIfNeeded();
    }
}
