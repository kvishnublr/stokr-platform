#!/bin/bash
set -e
echo "=== broker_accounts data ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, broker_name, client_id, status, access_token IS NOT NULL as has_token, token_expiry, auto_reconnect, last_auto_reconnect FROM broker_accounts;"
echo ""
echo "=== broker_token_refresh_log ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT * FROM broker_token_refresh_log ORDER BY created_at DESC LIMIT 5;" 2>/dev/null || echo "no data"
