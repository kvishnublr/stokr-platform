#!/usr/bin/env python3
import paramiko
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=120):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    c.close()
    return out.strip()


if __name__ == "__main__":
    print(run(f"cd {BASE} && git fetch origin Release_v1 && git rev-parse HEAD origin/Release_v1"))
    print(run(f"cd {BASE} && git branch -vv"))
    print(run("sleep 30 && curl -sf http://127.0.0.1:8080/actuator/health", timeout=60))
    print(run("docker exec stokr-api printenv STOKR_GIT_COMMIT 2>/dev/null || echo none"))
