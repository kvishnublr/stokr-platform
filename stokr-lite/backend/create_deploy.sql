-- Get strategy ID
SELECT id FROM strategies WHERE strategy_type = 'OVERSOLD_BOUNCE';

-- Create paper deployment
INSERT INTO deployments (user_id, strategy_id, broker_account_id, mode, capital, status)
SELECT 1, s.id, NULL, 'PAPER', 100000, 'ACTIVE'
FROM strategies s WHERE s.strategy_type = 'OVERSOLD_BOUNCE'
AND NOT EXISTS (SELECT 1 FROM deployments WHERE strategy_id = s.id AND status = 'ACTIVE');

SELECT d.id, d.strategy_id, d.mode, d.capital, d.status
FROM deployments d JOIN strategies s ON d.strategy_id = s.id
WHERE s.strategy_type = 'OVERSOLD_BOUNCE';
