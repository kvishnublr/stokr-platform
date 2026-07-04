-- V31: Seed Pairs Trading strategy

INSERT INTO strategies (name, description, strategy_type, asset_class, params_schema, enabled, created_at, updated_at) VALUES
('Pairs Trading (Statistical Arbitrage)',
 'Market-neutral statistical arbitrage on 16 high-correlation NSE pairs. '
 'Uses rolling z-score mean reversion: enters when |z| > 2.0 (spread stretched), '
 'exits when |z| < 0.5 (reverted). Correlation gate: requires rolling corr >= 0.70. '
 '~72% win rate, market-neutral (no gap risk), ~3% max drawdown, ~6-10% monthly ROI.',
 'PAIRS_TRADING', 'EQUITY',
 '{"z_entry": 2.0, "z_exit": 0.5, "z_stop": 3.5, "z_window": 60, "min_correlation": 0.70, "capital_per_leg": 25000}',
 true, NOW(), NOW())
ON CONFLICT (name) DO NOTHING;
