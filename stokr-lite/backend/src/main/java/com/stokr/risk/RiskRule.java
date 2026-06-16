package com.stokr.risk;

public interface RiskRule {

    String getRuleName();

    RiskDecision evaluate(RiskContext context);

    record RiskDecision(boolean passed, String reason) {
        public static RiskDecision pass() {
            return new RiskDecision(true, "OK");
        }
        public static RiskDecision fail(String reason) {
            return new RiskDecision(false, reason);
        }
    }
}
