#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)


def run(cmd, t=900):
    print("$", cmd[:140])
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(out[-2500:] if len(out) > 2500 else out)


run("cd /opt/stokr/stokr-platform && git pull origin Release_v1")
run("cd /opt/stokr/stokr-platform && docker rm -f stokr-api 2>/dev/null; ./deploy.sh api", t=1200)
run("docker logs stokr-api --since 3m 2>&1 | grep -i TIME_WINDOW | tail -5")
c.close()
