#!/usr/bin/env python3
import paramiko

PAPER = "b2e3ca37-2a71-448e-a701-48ff71b9aab5"
LIVE = "57bba5db-9725-45da-baa5-942f95290cfc"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmd = f"""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select id, signal_id, execution_mode, state, broker_order_id, broker_external_order_id, reject_reason, side, quantity
from oms_orders where id in ('{PAPER}'::uuid, '{LIVE}'::uuid);
"
docker logs stokr-api 2>&1 | grep -E '{LIVE}|{PAPER}|place|submit|FILLED|REJECTED|broker' | tail -30
"""
_, o, e = c.exec_command(cmd, timeout=120)
print((o.read() + e.read()).decode())
c.close()
