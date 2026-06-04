#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select id, strategy_name, signal_type, user_id from strategy_signals
where created_at >= current_date order by created_at;" """,
    timeout=60,
)
print((o.read() + e.read()).decode())
c.close()
