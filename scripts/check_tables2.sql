-- Check strategy_signals columns
SELECT column_name FROM information_schema.columns WHERE table_name = 'strategy_signals' ORDER BY ordinal_position;

-- Check orders columns
SELECT column_name FROM information_schema.columns WHERE table_name = 'orders' ORDER BY ordinal_position;

-- Check trades columns
SELECT column_name FROM information_schema.columns WHERE table_name = 'trades' ORDER BY ordinal_position;
