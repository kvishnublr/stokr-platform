-- Add timeframe column to strategies: INTRADAY (default) or DAILY
ALTER TABLE strategies ADD COLUMN IF NOT EXISTS timeframe VARCHAR(20) DEFAULT 'INTRADAY';

-- Seed OVERSOLD_BOUNCE strategy for daily evaluation
INSERT INTO strategies (name, strategy_type, description, asset_class, enabled, timeframe)
VALUES ('Oversold Bounce', 'OVERSOLD_BOUNCE', 'Buy stocks that dropped >3%, sell next day. Data-driven mean reversion.', 'EQUITY', true, 'DAILY')
ON CONFLICT (strategy_type) DO UPDATE SET timeframe = 'DAILY', enabled = true;
