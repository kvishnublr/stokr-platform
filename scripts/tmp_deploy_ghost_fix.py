#!/usr/bin/env python3
import paramiko
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=3600):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    return (o.read() + e.read()).decode(errors="replace").strip()


if __name__ == "__main__":
    for cmd in [
        f"cd {BASE} && git pull origin Release_v1",
        f"cd {BASE} && git rev-parse --short HEAD && git log -1 --oneline",
        f"cd {BASE} && docker compose --profile app build api 2>&1 | tail -12",
        f"cd {BASE} && docker compose --profile app up -d --no-deps api 2>&1",
    ]:
        print(f"\n>>> {cmd[:90]}")
        print(run(cmd)[-2500:])

    time.sleep(55)
    print("\n>>> health")
    print(run("curl -sf http://127.0.0.1:8080/actuator/health"))
