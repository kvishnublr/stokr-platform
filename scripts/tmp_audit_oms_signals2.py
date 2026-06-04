#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmd = r"""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT o.id, o.signal_id, o.deleted, o.state, o.created_at
FROM oms_orders o
WHERE o.signal_id IN (SELECT id FROM strategy_signals WHERE created_at >= CURRENT_DATE)
ORDER BY o.created_at;
"
docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_oms_orders_user_signal_live';
"
"""
_, o, e = c.exec_command(cmd, timeout=120)
print((o.read() + e.read()).decode())
c.close()
