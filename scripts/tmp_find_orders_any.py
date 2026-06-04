#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select id, deleted, execution_mode, state, signal_id from oms_orders
where id in ('b2e3ca37-2a71-448e-a701-48ff71b9aab5'::uuid, '57bba5db-9725-45da-baa5-942f95290cfc'::uuid);
select id, deleted, amount, order_id from strategy_capital_reservations
where signal_id = '53f8ca95-6e7d-459a-ba20-fe429c09812e'::uuid limit 5;
" """,
    timeout=60,
)
print((o.read() + e.read()).decode())
c.close()
