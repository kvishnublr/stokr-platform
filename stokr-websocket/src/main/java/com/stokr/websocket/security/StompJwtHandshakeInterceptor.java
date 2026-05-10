package com.stokr.websocket.security;

import com.stokr.auth.jwt.JwtService;
import com.stokr.auth.security.StokrUserDetails;
import com.stokr.auth.security.StokrUserDetailsService;
import com.stokr.auth.security.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * Authenticates SockJS/WebSocket handshake via {@code access_token} query param or {@code Authorization: Bearer}.
 */
@Component
@RequiredArgsConstructor
public class StompJwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final StokrUserDetailsService userDetailsService;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        HttpServletRequest req = servletRequest.getServletRequest();
        String token = req.getParameter("access_token");
        if (token == null || token.isBlank()) {
            String header = req.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                token = header.substring(7);
            }
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        if (tokenBlacklistService.isDenied(token)) {
            return false;
        }
        try {
            Claims claims = jwtService.parse(token);
            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            if (email == null || email.isBlank()) {
                return false;
            }
            StokrUserDetails user = (StokrUserDetails) userDetailsService.loadUserByUsername(email);
            if (!user.getId().equals(userId)) {
                return false;
            }
            attributes.put(StompWsSecurityConstants.SESSION_USER_ID, userId.toString());
            attributes.put(StompWsSecurityConstants.SESSION_EMAIL, email);
            attributes.put(
                    StompWsSecurityConstants.SESSION_ROLE_NAMES,
                    user.getAuthorities().stream().map(a -> a.getAuthority()).toList()
            );
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // no-op
    }
}
