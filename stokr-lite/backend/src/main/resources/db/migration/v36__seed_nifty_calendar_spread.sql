-- V36: Seed NIFTY Weekly Calendar Spread strategy

INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES
('NIFTY Weekly Calendar Spread',
 'Sell current-week ATM CE, buy next-week ATM CE. Max loss capped at debit (~Rs.1,750/lot). '
 '75% WR, 9.5% monthly ROI. Survives crashes & black swans — theta decay works regardless of direction. '
 'Entry: Monday 9:20 AM. Exit: Wednesday 3:15 PM. Filters: ADR<1.5%, gap<1%, VIX<25, no event weeks.',
 'NIFTY_CALENDAR_SPREAD', 'NFO',
 '{"max_positions": 1, "lots": 1, "capital_per_trade": 1750, "entry_day": "MONDAY", "exit_day": "WEDNESDAY"}',
 true, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
