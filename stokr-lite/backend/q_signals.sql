-- Check deployment statuses
SELECT id, strategy_id, status, mode, capital FROM deployments ORDER BY id;

-- Check what strategy types are generating signals
SELECT strategy_type, count(*), max(created_at) as latest
FROM signals
GROUP BY strategy_type
ORDER BY latest DESC;

-- Check signals for OB specifically
SELECT count(*), max(created_at) FROM signals WHERE strategy_type ILIKE '%oversold%';

-- Check OB strategy registration
SELECT id, name, strategy_class, timeframe FROM strategies WHERE name ILIKE '%oversold%' OR name ILIKE '%bounce%';
