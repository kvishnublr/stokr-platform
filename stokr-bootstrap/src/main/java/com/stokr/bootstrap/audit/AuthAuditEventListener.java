package com.stokr.bootstrap.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * DISABLED: AuthAuditEventListener depends on removed module (stokr-admin).
 * This stub is kept to prevent compilation errors.
 * Audit logging is disabled for NSE_SPIKE_DETECTION V2.0 focus.
 */
@Component
public class AuthAuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditEventListener.class);

    public AuthAuditEventListener() {
        log.warn("AuthAuditEventListener is disabled (stub only)");
    }

    @Async
    @EventListener
    public void onEvent(Object e) {
        // Stub only - audit logging disabled
    }
}
