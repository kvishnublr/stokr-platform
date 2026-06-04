#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmd = (
    "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
    "\"UPDATE strategy_instances si SET runtime_state='RUNNING', enabled=true "
    "FROM strategy_definitions sd WHERE sd.id=si.definition_id "
    "AND sd.strategy_key='INDEX_HUNT' AND si.runtime_state='PAUSED'; "
    "SELECT si.id, si.execution_mode, si.runtime_state FROM strategy_instances si "
    "JOIN strategy_definitions sd ON sd.id=si.definition_id WHERE sd.strategy_key='INDEX_HUNT';\""
)
_, o, e = c.exec_command(cmd)
print((o.read() + e.read()).decode())
c.close()
