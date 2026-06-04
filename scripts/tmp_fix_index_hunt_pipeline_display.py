#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)


def run(cmd, t=900):
    print("$", cmd[:160])
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(out[-3500:] if len(out) > 3500 else out)
    return o.channel.recv_exit_status()


run("cd /opt/stokr/stokr-platform && git pull origin Release_v1")
run("cd /opt/stokr/stokr-platform && docker rm -f stokr-api 2>/dev/null; ./deploy.sh api", t=1200)
run(
    "docker exec stokr-postgres psql -U postgres -d stokr_platform -c "
    "\"UPDATE strategy_signals SET pipeline='BOTH' WHERE strategy_name='INDEX_HUNT' "
    "AND created_at >= CURRENT_DATE AND (pipeline IS NULL OR pipeline='PAPER'); "
    "SELECT pipeline, count(*) FROM strategy_signals WHERE strategy_name='INDEX_HUNT' "
    "AND created_at >= CURRENT_DATE GROUP BY pipeline ORDER BY 1;\""
)
run("cd /opt/stokr/stokr-platform && git pull origin Release_v1 && ./deploy.sh ui", t=600)
c.close()
