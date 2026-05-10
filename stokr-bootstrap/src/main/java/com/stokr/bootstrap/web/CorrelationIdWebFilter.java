package com.stokr.bootstrap.web;

import com.stokr.common.correlation.CorrelationIdConstants;
import com.stokr.common.correlation.CorrelationIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String incoming = request.getHeader(CorrelationIdConstants.HEADER);
            String correlationId = (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;
            CorrelationIdHolder.set(correlationId);
            MDC.put("correlationId", correlationId);
            response.setHeader(CorrelationIdConstants.HEADER, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            CorrelationIdHolder.clear();
            MDC.remove("correlationId");
        }
    }
}
