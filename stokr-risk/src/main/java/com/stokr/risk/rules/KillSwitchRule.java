package com.stokr.risk.rules;

import com.stokr.common.simulation.SimulationModeService;
import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.risk.service.KillSwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
@RequiredArgsConstructor
public class KillSwitchRule implements RiskRule {

    private final KillSwitchService killSwitchService;
    private final SimulationModeService simulationModeService;

    @Override
    public String code() {
        return "KILL_SWITCH";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        if (context.order() != null
                && context.order().isSimulation()
                && simulationModeService.isActive()) {
            return RiskDecision.ok();
        }
        if (killSwitchService.isEnabled()) {
            return RiskDecision.reject(code(), "Kill switch enabled");
        }
        return RiskDecision.ok();
    }
}
