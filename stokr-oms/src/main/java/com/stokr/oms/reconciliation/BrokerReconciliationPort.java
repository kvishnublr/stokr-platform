package com.stokr.oms.reconciliation;

import java.util.UUID;

/**
 * Foundation for broker position / execution drift reconciliation (PR-4).
 * Live implementations compare OMS truth vs broker truth without coupling the core engine to a vendor.
 */
public interface BrokerReconciliationPort {

    /**
     * Lightweight drift probe; default implementation logs structured diagnostics only.
     */
    void probeExecutionDrift(UUID userId, String brokerVendor);
}
