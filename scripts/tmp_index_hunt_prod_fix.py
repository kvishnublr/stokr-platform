#!/usr/bin/env python3
"""Deploy INDEX_HUNT BOTH fix + prod toggles."""
import paramiko
import time

BASE = "/opt/stokr/stokr-platform"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)


def run(cmd, t=900):
    print("$", cmd[:160] + ("..." if len(cmd) > 160 else ""))
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(out[-4000:] if len(out) > 4000 else out)
    return o.channel.recv_exit_status()


run(f"cd {BASE} && git fetch origin Release_v1 && git checkout Release_v1 && git pull origin Release_v1")
code = run(f"cd {BASE} && bash deploy.sh api")
if code != 0:
    run("docker rm -f stokr-api 2>/dev/null; cd /opt/stokr/stokr-platform && docker compose --profile app up -d api")
run(f"cd {BASE} && bash deploy.sh ui")
time.sleep(120)
run('docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT version, success FROM flyway_schema_history WHERE version=\'87\';"')
run("docker exec stokr-redis redis-cli SET stokr:strategy:INDEX_HUNT:enabled 1")
run("docker exec stokr-redis redis-cli GET stokr:strategy:INDEX_HUNT:enabled")
run('docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT strategy_key, execution_mode, live_enabled, supports_live FROM strategy_execution_configs c JOIN strategy_definitions d ON d.strategy_key=c.strategy_key AND d.deleted=false WHERE c.strategy_key=\'INDEX_HUNT\' AND c.user_id IS NULL AND c.deleted=false;"')
run('docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT si.execution_mode, si.runtime_state FROM strategy_instances si JOIN strategy_definitions sd ON sd.id=si.definition_id WHERE sd.strategy_key=\'INDEX_HUNT\' AND si.deleted=false;"')
run("docker exec stokr-api printenv | grep -E 'INDEX_HUNT|EXEC_MODE' || true")
run("docker logs stokr-api 2>&1 | grep -E 'catalog.scan.signal strategyKey=INDEX_HUNT|skip_broker.*INDEX_HUNT|both_mode.dispatched|fanout.dispatched.*INDEX_HUNT' | tail -12")
c.close()
