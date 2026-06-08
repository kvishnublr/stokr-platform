#!/usr/bin/env python3
import paramiko
import time

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run_ssh(cmd: str) -> str:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, stdout, stderr = c.exec_command(cmd)
    out = (stdout.read() + stderr.read()).decode("utf-8", "replace")
    c.close()
    return out


def main() -> None:
    print("=== removing stokr-postgres container only (keep volumes) ===", flush=True)
    print(run_ssh("docker rm -f stokr-postgres || true").strip(), flush=True)

    print("\n=== compose up postgres (profile app) ===", flush=True)
    print(run_ssh(f"cd {BASE} && docker compose --profile app up -d postgres").strip(), flush=True)

    print("\n=== waiting 25s ===", flush=True)
    time.sleep(25)

    print("\n=== networks after recreate ===", flush=True)
    print("stokr-api:", run_ssh("docker inspect stokr-api --format '{{json .NetworkSettings.Networks}}'").strip())
    print("stokr-postgres:", run_ssh("docker inspect stokr-postgres --format '{{json .NetworkSettings.Networks}}'").strip())

    print("\n=== health checks (best-effort) ===", flush=True)
    print("local:", run_ssh("curl -sf http://127.0.0.1:8080/actuator/health || true").strip())
    print("public:", run_ssh("curl -sf https://stokr.in/api/actuator/health || true").strip())

    print("\n=== done ===", flush=True)


if __name__ == "__main__":
    main()

