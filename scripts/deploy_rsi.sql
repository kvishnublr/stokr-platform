INSERT INTO strategy_universe_mappings (strategy_id, universe_group_id, runtime_enabled, max_positions, capital_limit, risk_profile, scan_interval_seconds)
VALUES (31, 2, true, 3, 25000.00, 'MEDIUM', 60)
RETURNING id;
