#!/usr/bin/env python3
"""Run exit_monitor on prod via SSH and print summary (for local agent loop)."""
from __future__ import annotations

import os
import sys

import paramiko

HOST = os.environ.get("STOKR_PROD_HOST", "173.249.55.84")
USER = os.environ.get("STOKR_PROD_USER", "root")
REMOTE_SCRIPT = "/opt/stokr/stokr-platform/scripts/exit_monitor.py"


def main() -> int:
    key_path = os.path.expanduser(os.environ.get("STOKR_SSH_KEY", "~/.ssh/id_rsa"))
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        client.connect(HOST, username=USER, key_filename=key_path, timeout=25)
    except Exception:
        pwd = os.environ.get("STOKR_PROD_SSH_PASS")
        if not pwd:
            print("SSH auth failed and STOKR_PROD_SSH_PASS not set", file=sys.stderr)
            return 2
        client.connect(HOST, username=USER, password=pwd, timeout=25)

    cmd = f"python3 {REMOTE_SCRIPT}; echo '---STATE---'; cat /var/log/stokr-exit-monitor.state.json 2>/dev/null || true; echo '---TAIL---'; tail -8 /var/log/stokr-exit-monitor.log 2>/dev/null || true"
    _, stdout, stderr = client.exec_command(cmd, timeout=120)
    out = (stdout.read() + stderr.read()).decode("utf-8", "replace")
    print(out)
    client.close()
    return 1 if " ALERT " in out or " WARNING " in out and "No missing" not in out else 0


if __name__ == "__main__":
    raise SystemExit(main())
