package com.stokr.broker.safety;

import com.stokr.common.simulation.SimulationModeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Hard block: while simulation runtime is active, no live broker API may place orders.
 */
@Component
@RequiredArgsConstructor
public class BrokerLiveOrderGuard {

    private static final Set<String> LIVE_BROKER_VENDORS = Set.of(
            "ZERODHA", "FYERS", "DHAN", "UPSTOX", "ANGEL", "GROWW", "ICICI"
    );

    private final SimulationModeService simulationModeService;

    public void assertLiveOrderAllowed(String vendorCode) {
        if (!simulationModeService.isActive()) {
            return;
        }
        if (vendorCode == null || vendorCode.isBlank()) {
            throw new SimulationBrokerBlockedException(
                    "Broker vendor required; live orders blocked while simulation mode is active");
        }
        String vendor = vendorCode.trim().toUpperCase();
        if ("SIMULATED".equals(vendor) || "SIM".equals(vendor)) {
            return;
        }
        if (LIVE_BROKER_VENDORS.contains(vendor)) {
            throw new SimulationBrokerBlockedException(
                    "Live broker order blocked while simulation mode is active: " + vendor);
        }
    }

    public static class SimulationBrokerBlockedException extends RuntimeException {
        public SimulationBrokerBlockedException(String message) {
            super(message);
        }
    }
}
