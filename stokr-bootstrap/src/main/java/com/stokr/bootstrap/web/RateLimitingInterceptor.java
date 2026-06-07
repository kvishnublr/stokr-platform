package com.stokr.bootstrap.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.bootstrap.service.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

@Slf4j
@Component
@Profile("v2")
@RequiredArgsConstructor
public class RateLimitingInterceptor implements HandlerInterceptor {

    private static final UUID ANONYMOUS_USER = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final RateLimiterService rateLimiter;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {

        if (!rateLimiter.isEnabled()) {
            return true;
        }

        UUID userId = extractUserId(request);
        String path = request.getRequestURI();
        RateLimiterService.RateLimitEndpoint endpoint = getEndpoint(path);

        if (endpoint == null) {
            return true;
        }

        if (rateLimiter.allowRequest(userId, endpoint)) {
            int tokensRemaining = rateLimiter.getTokensRemaining(userId, endpoint);
            response.setHeader("X-RateLimit-Remaining", String.valueOf(tokensRemaining));
            response.setHeader("X-RateLimit-Reset", String.valueOf(rateLimiter.getTimeUntilReset(userId, endpoint)));
            log.debug("Request allowed: {} {} (user: {})", request.getMethod(), path, userId);
            return true;
        }

        int queuedRequests = rateLimiter.getQueuedRequestCount(userId, endpoint);
        long resetTime = rateLimiter.getTimeUntilReset(userId, endpoint);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(Math.max(1, resetTime / 1000)));
        response.setHeader("X-RateLimit-Queued", String.valueOf(queuedRequests));
        response.setContentType("application/json");

        String json = String.format(
                "{\"error\":\"Rate limit exceeded\",\"queued\":%d,\"retryAfterSeconds\":%d}",
                queuedRequests,
                Math.max(1, resetTime / 1000)
        );
        response.getWriter().write(json);

        log.warn("Request rate limited: {} {} (user: {}, queued: {})",
                request.getMethod(), path, userId, queuedRequests);
        return false;
    }

    private RateLimiterService.RateLimitEndpoint getEndpoint(String path) {
        if (path.startsWith("/api/orders")) {
            return RateLimiterService.RateLimitEndpoint.ORDERS;
        } else if (path.startsWith("/api/signals")) {
            return RateLimiterService.RateLimitEndpoint.SIGNALS;
        } else if (path.startsWith("/api/portfolio")) {
            return RateLimiterService.RateLimitEndpoint.PORTFOLIO;
        }
        return null;
    }

    private UUID extractUserId(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader != null) {
            try {
                return UUID.fromString(userIdHeader);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid X-User-Id header: {}", userIdHeader);
            }
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof StokrUserDetails details) {
                return details.getId();
            }
        }

        return ANONYMOUS_USER;
    }
}
