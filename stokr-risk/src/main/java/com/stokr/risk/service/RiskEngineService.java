package com.stokr.risk.service;

import com.stokr.risk.api.RiskRule;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RiskEngineService {

    private final List<RiskRule> rules;

    public RiskDecision evaluate(RiskContext context) {
        for (RiskRule rule : rules) {
            RiskDecision d = rule.evaluate(context);
            if (!d.allowed()) {
                return d;
            }
        }
        return RiskDecision.ok();
    }
}
