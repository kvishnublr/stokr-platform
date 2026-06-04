#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

sql = """
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT o.id, o.signal_id, o.deleted, o.status, o.created_at, o.user_id
FROM oms_orders o
WHERE o.created_at >= CURRENT_DATE OR o.signal_id IN (
  SELECT id FROM strategy_signals WHERE created_at >= CURRENT_DATE
)
ORDER BY o.created_at;
"
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT COUNT(*) AS orders_today FROM oms_orders WHERE deleted=false AND created_at >= CURRENT_DATE;
"
docker logs stokr-api 2>&1 | grep -E 'orphan_signal|oms.intent.sync_complete|redispatch' | tail -40
"""

_, o, e = c.exec_command(sql, timeout=120)
print((o.read() + e.read()).decode())
c.close()
