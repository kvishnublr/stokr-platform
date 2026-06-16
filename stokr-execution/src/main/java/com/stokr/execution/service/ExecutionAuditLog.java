package com.stokr.execution.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ExecutionAuditLog {

    private static final Logger log = LoggerFactory.getLogger(ExecutionAuditLog.class);

    public void record(String phase, UUID orderId, String detail) {
        log.info("execution_audit phase={} orderId={} detail={}", phase, orderId, detail);
    }
}
