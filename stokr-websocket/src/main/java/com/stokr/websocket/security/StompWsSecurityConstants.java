package com.stokr.websocket.security;

/**
 * WebSocket session attributes populated during JWT handshake ({@link StompJwtHandshakeInterceptor}).
 */
public final class StompWsSecurityConstants {

    public static final String SESSION_USER_ID = "stokr.ws.userId";

    public static final String SESSION_EMAIL = "stokr.ws.email";

    /** Immutable copy of role names for topic authorization. */
    public static final String SESSION_ROLE_NAMES = "stokr.ws.roleNames";

    private StompWsSecurityConstants() {
    }
}
