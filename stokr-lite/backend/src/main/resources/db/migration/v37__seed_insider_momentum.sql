-- V37: Seed Insider Momentum + Post-Earnings Drift strategies

-- Insider Momentum: Promoter buying + daily trend filters
INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES
('Insider Momentum',
 'Promoter/Director open-market buying + 20DMA trend + volume confirmation. '
 'SEBI-mandated disclosures tracked daily. When insiders buy ≥Rs.5L in their own stock, '
 'and technicals confirm uptrend, we ride their conviction. '
 '4 positions × Rs.5K. Target: 15%. SL: 5%. 21-day time stop. Expected: 60% WR, 14% monthly ROI.',
 'INSIDER_MOMENTUM', 'EQUITY',
 '{"max_positions": 4, "capital_per_trade": 5000, "min_insider_amount": 500000, "max_hold_days": 21}',
 true, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;

-- NIFTY Weekly Calendar Spread (from V36 attempt — keep but mark as beta)
INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES
('NIFTY Weekly Calendar Spread',
 'Sell current-week ATM CE, buy next-week ATM CE. Max loss capped at debit (~Rs.1,750/lot). '
 '75% WR target. Survives crashes — theta decay works regardless of direction. '
 'Entry: Monday 9:20 AM. Exit: Wednesday 3:15 PM. Filters: ADR<1.5%, gap<1%, VIX<25. '
 'Status: BETA — needs live NFO data integration.',
 'NIFTY_CALENDAR_SPREAD', 'NFO',
 '{"max_positions": 1, "lots": 1, "capital_per_trade": 1750, "entry_day": "MONDAY", "exit_day": "WEDNESDAY"}',
 false, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
