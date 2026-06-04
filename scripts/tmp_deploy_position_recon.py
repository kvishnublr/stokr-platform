#!/usr/bin/env python3
"""Deploy position reconciliation to prod."""
import paramiko
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=900):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    c.close()
    return out.strip()


if __name__ == "__main__":
    steps = [
        (f"cd {BASE} && git pull origin Release_v1 && git rev-parse --short HEAD", 120),
        (f"cd {BASE} && ./deploy.sh api ui", 900),
        ("curl -sf http://127.0.0.1:8080/actuator/health", 60),
        ("docker exec stokr-api printenv STOKR_GIT_COMMIT 2>/dev/null || echo none", 30),
        ("docker compose --profile app ps api ui 2>/dev/null | head -5", 30),
    ]
    for cmd, timeout in steps:
        print(f"\n=== {cmd[:100]} ===")
        print(run(cmd, timeout=timeout))
