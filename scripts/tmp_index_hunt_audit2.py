#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT id, username, enabled, deleted, live_trading_approved FROM auth_users WHERE id='6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4';" """,
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT si.id::text, si.user_id::text, si.execution_mode, si.runtime_state FROM strategy_instances si JOIN strategy_definitions sd ON sd.id=si.definition_id WHERE sd.strategy_key='INDEX_HUNT' AND si.deleted=false LIMIT 5;" """,
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT o.symbol, o.execution_mode, o.state, o.reject_reason, o.created_at FROM oms_orders o WHERE o.deleted=false AND o.strategy_key='INDEX_HUNT' AND o.symbol IN ('NESTLEIND','ICICIBANK') AND o.created_at>=CURRENT_DATE ORDER BY o.created_at;" """,
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT vendor_code, status, health_status FROM broker_accounts WHERE user_id='6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4' AND deleted=false;" """,
]
for cmd in cmds:
    print("$", cmd[:80])
    _, o, e = c.exec_command(cmd, timeout=60)
    print((o.read() + e.read()).decode())
c.close()
