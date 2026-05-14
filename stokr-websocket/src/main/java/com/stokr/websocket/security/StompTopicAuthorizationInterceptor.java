package com.stokr.websocket.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Enforces user-scoped STOMP subscriptions so clients cannot read another user's fan-out topics.
 */
@Component
@Slf4j
public class StompTopicAuthorizationInterceptor implements ChannelInterceptor {

    private static final String PREFIX_ORDERS = "/topic/orders.";
    private static final String PREFIX_POSITIONS = "/topic/positions.";
    private static final String PREFIX_PNL = "/topic/pnl.";
    private static final String PREFIX_SIGNALS = "/topic/signals.";
    private static final String PREFIX_STRATEGIES = "/topic/strategies.";
    private static final String PREFIX_BACKTEST_JOBS = "/topic/backtest.jobs.";
    private static final String PREFIX_MARKET = "/topic/market.";
    private static final String PREFIX_ADMIN = "/topic/admin.";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand cmd = accessor.getCommand();
        if (cmd != StompCommand.CONNECT && cmd != StompCommand.SUBSCRIBE) {
            return message;
        }
        Map<String, Object> sess = accessor.getSessionAttributes();
        String userId = sess != null ? (String) sess.get(StompWsSecurityConstants.SESSION_USER_ID) : null;
        if (userId == null || userId.isBlank()) {
            throw new MessageDeliveryException("WebSocket session missing authentication");
        }
        if (cmd == StompCommand.CONNECT) {
            return message;
        }
        String dest = accessor.getDestination();
        if (dest == null || dest.isBlank()) {
            throw new MessageDeliveryException("SUBSCRIBE missing destination");
        }
        @SuppressWarnings("unchecked")
        List<String> roles = sess.get(StompWsSecurityConstants.SESSION_ROLE_NAMES) instanceof List<?>
                ? (List<String>) sess.get(StompWsSecurityConstants.SESSION_ROLE_NAMES)
                : List.of();

        if (dest.startsWith(PREFIX_ADMIN)) {
            if (!roles.contains("ROLE_ADMIN")) {
                log.warn("stomp.forbidden.admin dest={} userId={}", dest, userId);
                throw new MessageDeliveryException("Forbidden admin subscription");
            }
            return message;
        }
        if (dest.startsWith(PREFIX_MARKET)) {
            return message;
        }
        if (matchesUserScoped(dest, PREFIX_ORDERS, userId)
                || matchesUserScoped(dest, PREFIX_POSITIONS, userId)
                || matchesUserScoped(dest, PREFIX_PNL, userId)
                || matchesUserScoped(dest, PREFIX_SIGNALS, userId)
                || matchesUserScoped(dest, PREFIX_STRATEGIES, userId)
                || matchesUserScoped(dest, PREFIX_BACKTEST_JOBS, userId)) {
            return message;
        }
        log.warn("stomp.forbidden.unknown dest={} userId={}", dest, userId);
        throw new MessageDeliveryException("Subscription not allowed");
    }

    private static boolean matchesUserScoped(String dest, String prefix, String expectedUserId) {
        if (!dest.startsWith(prefix)) {
            return false;
        }
        String suffix = dest.substring(prefix.length());
        return suffix.equals(expectedUserId);
    }
}
