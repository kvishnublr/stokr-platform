#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"

COMMANDS = [
    'docker ps --filter name=stokr-api --format "{{.Names}} {{.Status}}"',
    "curl -sf http://localhost:8080/actuator/health || echo HEALTH_FAIL",
    f"cd {BASE} && git rev-parse --short HEAD",
    "docker logs stokr-api 2>&1 | tail -30",
]


def main():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PWD, timeout=30)
    for cmd in COMMANDS:
        print(f"\n>>> {cmd}")
        _, stdout, stderr = client.exec_command(cmd, timeout=120)
        out = stdout.read().decode(errors="replace")
        err = stderr.read().decode(errors="replace")
        if out.strip():
            print(out.strip())
        if err.strip():
            print("STDERR:", err.strip())
    client.close()


if __name__ == "__main__":
    main()
