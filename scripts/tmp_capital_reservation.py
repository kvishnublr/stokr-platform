#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select order_id, status, reserved_amount, deleted from strategy_capital_reservations
where signal_id = '53f8ca95-6e7d-459a-ba20-fe429c09812e'::uuid limit 3;
" """,
    timeout=60,
)
print((o.read() + e.read()).decode())
c.close()
