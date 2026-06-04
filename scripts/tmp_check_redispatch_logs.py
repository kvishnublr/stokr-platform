#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

cmds = [
    "docker logs stokr-api 2>&1 | tail -80",
    'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "\\d strategy_signals" | head -40',
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select s.id, s.signal_type, s.is_test_trade, s.created_at
from strategy_signals s
where s.deleted = false and s.created_at >= current_date
order by s.created_at;" """,
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select s.id from strategy_signals s
where s.deleted = false
  and s.created_at >= current_date::timestamptz
  and coalesce(s.is_test_trade, false) = false
  and s.signal_type is not null
  and s.signal_type <> 'HOLD'
  and not exists (select 1 from oms_orders o where o.deleted = false and o.signal_id = s.id)
order by s.created_at asc limit 50;" """,
]

for cmd in cmds:
    print("\n$", cmd[:120], "...")
    _, o, e = c.exec_command(cmd, timeout=120)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(out[-5000:])

c.close()
