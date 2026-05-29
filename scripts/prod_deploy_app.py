#!/usr/bin/env python3
"""Pull Release_v1 and rebuild api + ui."""
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"

COMMANDS = [
    f"cd {BASE} && git fetch origin && git checkout Release_v1 && git pull origin Release_v1",
    f"cd {BASE} && git log -1 --oneline",
    f"cd {BASE} && docker compose --profile app build api 2>&1 | tail -20",
    f"cd {BASE} && docker compose --profile app up -d --no-deps api 2>&1",
    "sleep 45",
    "curl -sf http://localhost:8080/actuator/health | head -c 200",
    f"cd {BASE} && docker compose --profile app build ui 2>&1 | tail -20",
    f"cd {BASE} && docker compose --profile app up -d --no-deps ui 2>&1",
    "docker ps --filter name=stokr --format '{{.Names}} {{.Status}}' | head -6",
]


def main():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PWD, timeout=30)
    for cmd in COMMANDS:
        print(f"\n>>> {cmd[:100]}")
        _, stdout, stderr = client.exec_command(cmd, timeout=3600)
        out = stdout.read().decode(errors="replace")
        err = stderr.read().decode(errors="replace")
        if out.strip():
            print(out.strip()[-5000:])
        if err.strip():
            print("STDERR:", err.strip()[-1500:])
    client.close()
    print("\nAPP_DEPLOY_DONE")


if __name__ == "__main__":
    main()
