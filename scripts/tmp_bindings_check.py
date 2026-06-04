#!/usr/bin/env python3
import paramiko
c=paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy()); c.connect('173.249.55.84',username='root',password='Temp1234..',timeout=30)
sql="SELECT sd.strategy_key, sug.group_key, b.runtime_enabled FROM strategy_runtime_bindings b JOIN strategy_definitions sd ON sd.id=b.strategy_catalog_id JOIN strategy_universe_groups sug ON sug.id=b.universe_group_id WHERE sd.strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION') ORDER BY 1,2;"
cmd=f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql}"'
_,o,e=c.exec_command(cmd,timeout=60); print((o.read()+e.read()).decode())
