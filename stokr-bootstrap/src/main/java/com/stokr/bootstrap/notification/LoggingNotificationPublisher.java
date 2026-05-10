package com.stokr.bootstrap.notification;

import com.stokr.common.notification.NotificationEvent;
import com.stokr.common.notification.NotificationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dev-safe stand-in: records intent to deliver. Replace with Telegram/WhatsApp bridges in production.
 */
@Component
public class LoggingNotificationPublisher implements NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationPublisher.class);

    @Override
    public void publish(NotificationEvent event) {
        log.info("notification template={} channel={} userId={} payload={}",
                event.templateKey(), event.channel(), event.userId(), event.payload());
    }
}
