#!/usr/bin/env python3
import paramiko

NEW_SIGNAL = "53f8ca95-6e7d-459a-ba20-fe429c09812e"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
    f"""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select id, signal_id, execution_mode, state, broker_order_id, broker_external_order_id, reject_reason, created_at
from oms_orders where deleted=false and (signal_id = '{NEW_SIGNAL}'::uuid or id in (
  select id from oms_orders where deleted=false and created_at >= '2026-06-04 05:17:00+00'
))
order by created_at desc;" """,
    f"""docker logs stokr-api 2>&1 | grep '{NEW_SIGNAL}' | tail -40""",
]
for cmd in cmds:
    print("\n$", cmd[:100])
    _, o, e = c.exec_command(cmd, timeout=120)
    print((o.read() + e.read()).decode()[-6000:])
c.close()
