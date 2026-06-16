package com.stokr.oms.reconciliation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
public class LoggingBrokerReconciliationAdapter implements BrokerReconciliationPort {

    @Override
    public void probeExecutionDrift(UUID userId, String brokerVendor) {
        log.info("reconciliation.probe userId={} brokerVendor={} (foundation stub)", userId, brokerVendor);
    }
}
