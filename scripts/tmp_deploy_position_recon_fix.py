#!/usr/bin/env python3
import paramiko
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=300):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    c.close()
    return out.strip()


if __name__ == "__main__":
    cmds = [
        f"cd {BASE} && docker compose --profile app ps -a",
        f"cd {BASE} && docker compose --profile app rm -sf api 2>/dev/null; docker rm -f stokr-api dee4c45caa39_stokr-api 2>/dev/null; true",
        f"cd {BASE} && docker compose --profile app up -d api",
        "sleep 25 && curl -sf http://127.0.0.1:8080/actuator/health",
        "docker exec stokr-api printenv STOKR_GIT_COMMIT 2>/dev/null || echo none",
        f"cd {BASE} && ./deploy.sh ui",
        "sleep 5 && docker compose --profile app ps api ui 2>/dev/null | head -6",
    ]
    for cmd in cmds:
        print(f"\n=== {cmd[:120]} ===")
        print(run(cmd))
