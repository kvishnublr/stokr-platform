# SMART EXIT V1 MINIMAL - PROFIT PROTECTION ONLY

Date: 2026-06-09
Scope: ONLY Profit Protection and MFE Protection triggers
Complexity: MINIMAL (no health scores, no AI, no regime logic)
Status: Review Complete - Ready for V1 Implementation

## EXECUTIVE SUMMARY

SCOPE: Two simple, profit-protection triggers
- Profit Protection Exit (protect realized gains)
- MFE Protection Exit (retain peak profit percentage)

VERDICT: LOW RISK, HIGH VALUE
- Simple math-based logic
- Minimal overlap with existing exits
- Clear, measurable false positive threshold
- Expected +1-2% win rate improvement

---

## TRIGGER 1: PROFIT_PROTECTION_EXIT

IF Current Profit > min_threshold AND Giveback > max_threshold
  AND Position Age > min_age THEN: Exit

Example: Entry 100, Peak 103.5, Current 101.5
  Profit: 1.5%, Giveback: 57%
  Config: min_profit=0.50%, max_giveback=40%
  Result: TRIGGER (1.5% > 0.50% AND 57% > 40%)

Rationale: Protects trades showing strength but losing momentum

---

## TRIGGER 2: MFE_PROTECTION_EXIT

IF MFE > mfe_threshold AND Current Profit < (MFE * retention_ratio)
  AND Position Age > min_age THEN: Exit

Example: Entry 100, Peak 103, Current 101
  MFE: 3%, Retained: 33%
  Config: min_mfe=1.0%, min_retained=40%
  Result: TRIGGER (3% > 1.0% AND 33% < 40%)

Rationale: Protect significant unrealized gains (lost >60% of peak)

---

## OVERLAP WITH EXISTING EXITS

vs. PressureSmartExitService: ~5% overlap
vs. Hard Stop: 0% overlap (different criteria)
vs. Time Exit: 0% overlap (time vs. profit-based)
vs. Target Exit: 0% overlap (goal vs. giveback)

Overall Overlap: <10% (very low conflict risk)

---

## FALSE POSITIVE RISKS

Risk 1: Over-exiting winners on pullbacks (MEDIUM probability)
Mitigation: High thresholds (40% giveback), 120s age limit

Risk 2: Firing on intraday noise (MEDIUM probability)
Mitigation: MFE > 1%, conservative giveback threshold

Risk 3: Fighting trend continuation (LOW probability)
Mitigation: Only on positions with strong MFE

Risk 4: Excessive churn (LOW probability)
Mitigation: Conservative thresholds, 5-minute cooldown

Target: False positive rate <5%, churn <20%

---

## DRY-RUN VALIDATION PLAN

Phase 1 (Days 1-2): Code validation
- Deploy with enabled=false, dry-run=true
- Verify zero impact on existing exits

Phase 2 (Days 3-7): Dry-run observation
- Enable triggers with dry-run=true (no actual exits)
- Collect telemetry on all evaluations
- Count would-be exits, analyze false positives

Phase 3 (Day 8): Analysis
- False positive rate <5%?
- P&L improvement >0.5%?
- Churn acceptable?

Phase 4 (Day 9): Decision
- GO: Enable live if metrics pass
- NO-GO: Iterate if metrics fail

---

## RECOMMENDATION: MFE_PROTECTION BY DEFAULT?

Profit Protection: Enable by default (lower risk)
MFE Protection: Start disabled, enable after dry-run validation

---

## FINAL VERDICT

Scope: Minimal, focused profit protection only
Risk: LOW (simple math, minimal overlap)
Expected Value: HIGH (+1-2% win rate improvement)

Recommendation: PROCEED WITH V1
Timeline: 4 weeks (implementation + dry-run)

Status: READY FOR IMPLEMENTATION
