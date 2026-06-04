#!/usr/bin/env python3
import paramiko
import time

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)


def run(cmd, t=900):
    print("$", cmd[:140])
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(out[-5000:] if len(out) > 5000 else out)
    return o.channel.recv_exit_status()


run("cd /opt/stokr/stokr-platform && git pull origin Release_v1")
run(
    "cd /opt/stokr/stokr-platform && "
    "grep -q INDEX_HUNT .env 2>/dev/null || "
    "(grep -q '^STOKR_EXECUTION_MODES_LIVE_VALIDATED=' .env && "
    "sed -i '/^STOKR_EXECUTION_MODES_LIVE_VALIDATED=/s/$/,INDEX_HUNT/' .env || true); "
    "grep STOKR_EXEC_MODE_INDEX_HUNT .env 2>/dev/null || echo 'STOKR_EXEC_MODE_INDEX_HUNT not in env'; "
    "grep STOKR_EXECUTION_MODES_LIVE_VALIDATED .env 2>/dev/null || echo 'using app default live-validated'"
)
run("cd /opt/stokr/stokr-platform && docker rm -f stokr-api 2>/dev/null; ./deploy.sh api", t=1200)
time.sleep(120)
run(
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
SELECT version FROM flyway_schema_history WHERE version IN ('87','88') ORDER BY version;
SELECT execution_mode, live_enabled, paper_enabled FROM strategy_execution_configs WHERE strategy_key='INDEX_HUNT' AND user_id IS NULL;
SELECT si.execution_mode, si.runtime_state FROM strategy_instances si
  JOIN strategy_definitions sd ON sd.id=si.definition_id WHERE sd.strategy_key='INDEX_HUNT' LIMIT 5;
SELECT id, symbol, pipeline_mode, outcome_status, created_at FROM strategy_signals
  WHERE strategy_name='INDEX_HUNT' AND created_at >= CURRENT_DATE ORDER BY created_at DESC LIMIT 3;
" """
)
run("docker exec stokr-redis redis-cli GET stokr:strategy:INDEX_HUNT:enabled")
run("cd /opt/stokr/stokr-platform && git pull origin Release_v1 && ./deploy.sh ui", t=600)
c.close()
