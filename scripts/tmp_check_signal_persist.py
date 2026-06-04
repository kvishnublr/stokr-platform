#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select id, strategy_name, symbol, is_test_trade, left(reason,40) as reason, created_at
from strategy_signals where id='53f8ca95-6e7d-459a-ba20-fe429c09812e';
select id, execution_mode, state, signal_id from oms_orders
where created_at >= '2026-06-04 05:17:00+00' and deleted=false order by created_at;
" """,
    timeout=60,
)
print((o.read() + e.read()).decode())
c.close()
