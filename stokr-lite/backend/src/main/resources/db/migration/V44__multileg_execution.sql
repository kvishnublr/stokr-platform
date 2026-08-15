-- Generic N-leg execution support for spreads that aren't the CE+PE+FUT
-- conversion/reversal structure (Box/Vertical/Butterfly/Condor/Iron Condor
-- spreads have 2-4 same-side-option legs and no futures leg at all).
ALTER TABLE option_arb_opportunities ADD COLUMN IF NOT EXISTS legs_json TEXT;
ALTER TABLE live_positions ADD COLUMN IF NOT EXISTS legs_json TEXT;
