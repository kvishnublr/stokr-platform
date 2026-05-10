package com.stokr.common.notification;

/**
 * Pluggable delivery for operational alerts. Default implementation logs only until external providers are wired.
 */
@FunctionalInterface
public interface NotificationPublisher {
    void publish(NotificationEvent event);
}
