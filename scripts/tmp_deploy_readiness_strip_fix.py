#!/usr/bin/env python3
"""Patch readiness strip on prod UI without full git pull."""
import paramiko
from pathlib import Path

HOST, USER, PWD = "173.249.55.84", "root", "Temp1234.."
BASE = "/opt/stokr/stokr-platform"
ROOT = Path(__file__).resolve().parents[1]

FILES = [
    ROOT / "stokr-ui/src/components/admin/adminOpsModel.ts",
    ROOT / "stokr-ui/src/components/admin/cockpit/opsTypes.ts",
]


def main():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PWD, timeout=30)
    sftp = client.open_sftp()
    for local in FILES:
        rel = local.relative_to(ROOT).as_posix()
        remote = f"{BASE}/{rel}".replace("\\", "/")
        print(f"upload {rel} -> {remote}")
        sftp.put(str(local), remote)
    sftp.close()

    for cmd in [
        f"cd {BASE} && docker compose --profile app build ui 2>&1 | tail -20",
        f"cd {BASE} && docker compose --profile app up -d --no-deps ui 2>&1",
        "docker ps --filter name=stokr-ui --format '{{.Names}} {{.Status}}'",
    ]:
        print(f"\n>>> {cmd}")
        _, stdout, stderr = client.exec_command(cmd, timeout=2400)
        out = stdout.read().decode(errors="replace")
        err = stderr.read().decode(errors="replace")
        if out.strip():
            print(out.strip()[-3000:])
        if err.strip():
            print("STDERR:", err.strip()[-800:])
    client.close()
    print("\nDONE")


if __name__ == "__main__":
    main()
