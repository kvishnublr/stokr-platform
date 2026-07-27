#!/bin/bash
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT deployment_id, status, COUNT(*) as cnt FROM strategy_signals WHERE created_at >= '2026-07-16' GROUP BY deployment_id, status ORDER BY cnt DESC;"
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT COUNT(*) as total FROM strategy_signals WHERE created_at >= '2026-07-16';"
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT strategy_id, strategy_name FROM strategies WHERE enabled = true;"

