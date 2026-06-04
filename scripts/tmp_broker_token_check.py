#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
sql = """
SELECT user_id, vendor_code, status, token_expires_at IS NOT NULL AS has_expiry,
       access_token_enc IS NOT NULL AS has_access,
       refresh_token_enc IS NOT NULL AS has_refresh
FROM broker_accounts WHERE vendor_code='ZERODHA' AND deleted=false ORDER BY updated_at DESC LIMIT 5;
SELECT connection_state, token_expires_at, ingestion_paused,
       access_token_enc IS NOT NULL AS has_access,
       refresh_token_enc IS NOT NULL AS has_refresh
FROM platform_broker_feed_sessions WHERE vendor_code='ZERODHA' AND deleted=false;
"""
_, o, e = c.exec_command(
    f"docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c \"{sql}\"",
    timeout=60,
)
print((o.read() + e.read()).decode())
c.close()
