package com.stokr.bootstrap.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MdcWebFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String userId = request.getHeader("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                MDC.put("userId", userId);
            }
            String method = request.getMethod();
            MDC.put("httpMethod", method);
            String path = request.getRequestURI();
            MDC.put("requestPath", path);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
            MDC.remove("httpMethod");
            MDC.remove("requestPath");
        }
    }
}
