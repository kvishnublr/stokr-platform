#!/usr/bin/env python3
import paramiko
import time

BASE = "/opt/stokr/stokr-platform"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, t=900):
    print("$", cmd)
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(out[-3000:] if len(out) > 3000 else out)
    return o.channel.recv_exit_status()

run(f"cd {BASE} && git pull origin Release_v1")
code = run(f"cd {BASE} && bash deploy.sh api")
if code != 0:
    run("docker rm -f stokr-api 2>/dev/null; cd /opt/stokr/stokr-platform && docker compose --profile app up -d api")
time.sleep(150)
run('docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT version FROM flyway_schema_history WHERE version=\'85\';"')
run('docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT sd.strategy_key, sug.group_key FROM strategy_runtime_bindings b JOIN strategy_definitions sd ON sd.id=b.strategy_catalog_id JOIN strategy_universe_groups sug ON sug.id=b.universe_group_id WHERE sd.strategy_key LIKE \'%INR%\' ORDER BY 1;"')
run("docker logs stokr-api 2>&1 | grep -E 'token_cap|cds_backfill.filled|platform.ws.first_tick token=637187' | tail -15")
c.close()
