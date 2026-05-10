package com.stokr.bootstrap.notification;

import com.stokr.common.notification.NotificationEvent;
import com.stokr.common.notification.NotificationPublisher;
import com.stokr.user.telegram.TelegramDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Logs every notification and attempts Telegram delivery when the trader has bound chat id.
 */
@Component
@Primary
public class CompositeNotificationPublisher implements NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(CompositeNotificationPublisher.class);

    private final LoggingNotificationPublisher loggingNotificationPublisher;
    private final TelegramDeliveryService telegramDeliveryService;

    public CompositeNotificationPublisher(
            LoggingNotificationPublisher loggingNotificationPublisher,
            TelegramDeliveryService telegramDeliveryService
    ) {
        this.loggingNotificationPublisher = loggingNotificationPublisher;
        this.telegramDeliveryService = telegramDeliveryService;
    }

    @Override
    public void publish(NotificationEvent event) {
        loggingNotificationPublisher.publish(event);
        try {
            telegramDeliveryService.deliver(event);
        } catch (Exception ex) {
            log.warn("notification.telegram.bridge_failed template={} userId={}", event.templateKey(), event.userId(), ex);
        }
    }
}
