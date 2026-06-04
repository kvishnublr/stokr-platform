#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
select user_id, strategy_key, enabled, execution_mode from strategy_execution_configs
where upper(strategy_key)='ADV_CASH';
select id, execution_mode, state, reject_reason from oms_orders
where signal_id='be71d838-f8c5-412e-9718-c348e1559940'::uuid;
" """,
    timeout=60,
)
print((o.read() + e.read()).decode())
c.close()
