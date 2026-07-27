#!/bin/bash
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite << 'EOF'
-- Deployment columns
SELECT column_name, data_type FROM information_schema.columns WHERE table_name='deployments' ORDER BY ordinal_position;

-- Sample deployments
SELECT * FROM deployments ORDER BY id DESC LIMIT 5;

-- strategy_signals: all signals with join to strategies
SELECT ss.id, ss.symbol, ss.side, ss.entry_price, ss.stop_loss, ss.target, ss.status, ss.exit_type, ss.entry_time, ss.exit_time, ss.strategy_id, s.name as strategy_name
FROM strategy_signals ss
LEFT JOIN strategies s ON ss.strategy_id = s.id
ORDER BY ss.id DESC LIMIT 10;
EOF

