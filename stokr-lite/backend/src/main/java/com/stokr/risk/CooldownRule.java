package com.stokr.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CooldownRule implements RiskRule {

    @Value("${trading.default-cooldown-minutes:5}")
    private int cooldownMinutes;

    @Override
    public String getRuleName() {
        return "COOLDOWN";
    }

    @Override
    public RiskDecision evaluate(RiskContext context) {
        long elapsed = System.currentTimeMillis() - context.lastSignalTimeMs();
        long cooldownMs = cooldownMinutes * 60L * 1000L;

        if (context.lastSignalTimeMs() > 0 && elapsed < cooldownMs) {
            return RiskDecision.fail(String.format(
                    "Cooldown active: %d seconds remaining",
                    (cooldownMs - elapsed) / 1000));
        }
        return RiskDecision.pass();
    }
}
