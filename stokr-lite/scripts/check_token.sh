#!/bin/bash
set -e
echo "=== broker_accounts schema ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT column_name, data_type FROM information_schema.columns WHERE table_name='broker_accounts' ORDER BY ordinal_position;"
echo ""
echo "=== broker_accounts data ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT id, broker, access_token, api_key, expires_at, last_refresh FROM broker_accounts;"
echo ""
echo "=== broker_token_refresh_log ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT * FROM broker_token_refresh_log ORDER BY created_at DESC LIMIT 5;" 2>/dev/null || echo "no data"
echo ""
echo "=== How does Java app get access token? ==="
echo "Check BacktestController or ZerodhaClient for token loading..."

