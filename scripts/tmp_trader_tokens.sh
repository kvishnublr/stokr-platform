#!/bin/bash
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT ba.broker_user_id, ba.status, ba.vendor_code,
  ba.access_token_enc IS NOT NULL AS has_token,
  u.email, ba.updated_at AT TIME ZONE 'Asia/Kolkata' updated_ist
FROM broker_accounts ba
JOIN auth_users u ON u.id = ba.user_id
WHERE ba.deleted = false
ORDER BY ba.updated_at DESC LIMIT 10;
"

docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT vendor_code, connection_state, token_expires_at AT TIME ZONE 'Asia/Kolkata' exp_ist,
  access_token_enc IS NOT NULL AS has_token, updated_at AT TIME ZONE 'Asia/Kolkata' upd_ist
FROM platform_broker_feed_sessions
WHERE deleted = false;
"
