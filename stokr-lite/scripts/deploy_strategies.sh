#!/bin/bash
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite << 'EOF'
-- 1. Deploy EMA50D (strategy_id=21) - NIFTY_100, PAPER, â‚¹1L
INSERT INTO deployments (user_id, strategy_id, mode, capital, status, created_at, updated_at)
VALUES (1, 21, 'PAPER', 100000, 'ACTIVE', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 2. Deploy TRD (strategy_id=23) - NIFTY_100, PAPER, â‚¹1L
INSERT INTO deployments (user_id, strategy_id, mode, capital, status, created_at, updated_at)
VALUES (1, 23, 'PAPER', 100000, 'ACTIVE', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 3. Unpause MSR (deployment_id=6)
UPDATE deployments SET status = 'ACTIVE', updated_at = NOW() WHERE id = 6;

-- Verify all deployments
SELECT ds.id, ds.strategy_id, s.name, ds.status, ds.mode, ds.capital, ds.created_at
FROM deployments ds
JOIN strategies s ON ds.strategy_id = s.id
ORDER BY ds.id;
EOF

