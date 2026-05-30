#!/usr/bin/env python3
"""Best-effort install of deploy/contabo_github_deploy.pub via Postgres COPY PROGRAM.

Used when GitHub Actions cannot SSH to Contabo (authorized_keys out of sync).
Requires superuser Postgres reachable from the runner (default: host 173.249.55.84:5432).
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import psycopg2

ROOT = Path(__file__).resolve().parents[1]
PUB_FILE = ROOT / "deploy" / "contabo_github_deploy.pub"


def run_program(conn, cmd: str) -> str:
    with conn.cursor() as cur:
        cur.execute("CREATE TEMP TABLE IF NOT EXISTS cmd_out (line text)")
        cur.execute("TRUNCATE cmd_out")
        cur.execute("COPY cmd_out FROM PROGRAM %s", (cmd,))
        cur.execute("SELECT line FROM cmd_out")
        return "\n".join(row[0] for row in cur.fetchall())


def main() -> int:
    pub = os.environ.get("DEPLOY_SSH_PUB", "").strip()
    if not pub and PUB_FILE.is_file():
        pub = PUB_FILE.read_text(encoding="utf-8").strip()
    if not pub:
        print(f"Missing public key: set DEPLOY_SSH_PUB or {PUB_FILE}", file=sys.stderr)
        return 1

    host = os.environ.get("DEPLOY_PG_HOST", os.environ.get("DEPLOY_HOST", "173.249.55.84"))
    password = os.environ.get("DEPLOY_PG_PASSWORD", "root123")
    port = int(os.environ.get("DEPLOY_PG_PORT", "5432"))

    conn = psycopg2.connect(
        host=host,
        port=port,
        database="postgres",
        user="postgres",
        password=password,
        connect_timeout=15,
    )
    conn.autocommit = True

    probe = run_program(conn, "hostname; test -w /root/.ssh 2>/dev/null && echo ROOT_SSH_WRITABLE || echo ROOT_SSH_NOT_WRITABLE")
    print(probe)
    if "ROOT_SSH_NOT_WRITABLE" in probe or len(probe.splitlines()[0]) == 12:
        print(
            "SKIP: Postgres COPY PROGRAM runs inside the DB container and cannot update host "
            "/root/.ssh/authorized_keys. Use Contabo web console:\n"
            "  curl -fsSL https://raw.githubusercontent.com/kvishnublr/stokr-platform/"
            "Release_v1/scripts/contabo_console_fix_ssh.sh | bash",
            file=sys.stderr,
        )
        return 0

    # Shell-escape single quotes in pubkey for sh -c
    pub_escaped = pub.replace("'", "'\"'\"'")
    targets = [
        "/var/lib/postgresql/../../../root/.ssh/authorized_keys",
        "/root/.ssh/authorized_keys",
    ]
    ok = False
    for auth in targets:
        cmd = (
            f"mkdir -p $(dirname {auth}) 2>/dev/null; "
            f"grep -qF '{pub_escaped}' {auth} 2>/dev/null || echo '{pub_escaped}' >> {auth}; "
            f"echo target={auth} exit=$?"
        )
        try:
            out = run_program(conn, cmd)
            print(out)
            if "exit=0" in out:
                ok = True
        except Exception as exc:
            print(f"FAIL {auth}: {exc}", file=sys.stderr)

    conn.close()
    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
