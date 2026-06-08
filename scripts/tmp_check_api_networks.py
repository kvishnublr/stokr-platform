#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run_ssh(cmd: str) -> str:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, stdout, stderr = c.exec_command(cmd)
    out = (stdout.read() + stderr.read()).decode("utf-8", "replace")
    c.close()
    return out


def main() -> None:
    print("=== docker networks for stokr-api ===")
    print(run_ssh("docker inspect stokr-api --format '{{json .NetworkSettings.Networks}}'").strip())

    print("\n=== docker networks for stokr-postgres ===")
    print(run_ssh("docker inspect stokr-postgres --format '{{json .NetworkSettings.Networks}}'").strip())

    print("\n=== can stokr-api resolve postgres? (best-effort) ===")
    # Try a few common tools; if unavailable, errors will show.
    print(run_ssh("docker exec stokr-api sh -lc 'getent hosts postgres || true; nslookup postgres || true'").strip())

    print("\n=== done ===")


if __name__ == '__main__':
    main()

