package com.stokr.risk.rules;

import com.stokr.common.simulation.SimulationModeService;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Blocks LIVE routing when the operational broker circuit is open (disconnect / emergency path).
 */
@Component
@Order(11)
@RequiredArgsConstructor
public class BrokerDisconnectLiveHaltRule implements RiskRule {

    private final SimulationModeService simulationModeService;

    @Override
    public String code() {
        return "BROKER_CIRCUIT";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order() != null
                && context.order().isSimulation()
                && simulationModeService.isActive()) {
            return RiskDecision.ok();
        }
        if (context.order().getExecutionMode() != ExecutionMode.LIVE) {
            return RiskDecision.ok();
        }
        if (!context.brokerCircuitHalt()) {
            return RiskDecision.ok();
        }
        return RiskDecision.reject(code(), "Broker operational halt ??? live orders suspended");
    }
}
