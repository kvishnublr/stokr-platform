package com.stokr.risk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiskEngine {

    private final List<RiskRule> rules;
    private final KillSwitchService killSwitchService;

    /**
     * Evaluate all risk rules. Returns the first failure or pass if all rules pass.
     */
    public RiskRule.RiskDecision evaluate(RiskContext context) {
        // Check global kill switch first
        if (killSwitchService.isActive()) {
            log.warn("KILL SWITCH ACTIVE - rejecting order for deployment {}", context.deploymentId());
            return RiskRule.RiskDecision.fail("Global kill switch is active");
        }

        for (RiskRule rule : rules) {
            RiskRule.RiskDecision decision = rule.evaluate(context);
            if (!decision.passed()) {
                log.info("Risk rule {} FAILED for deployment {}: {}",
                        rule.getRuleName(), context.deploymentId(), decision.reason());
                return decision;
            }
        }

        log.debug("All risk rules passed for deployment {}", context.deploymentId());
        return RiskRule.RiskDecision.pass();
    }
}
