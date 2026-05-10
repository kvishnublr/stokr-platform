package com.stokr.risk.api;

import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;

public interface RiskRule {

    String code();

    RiskDecision evaluate(RiskContext context);
}
