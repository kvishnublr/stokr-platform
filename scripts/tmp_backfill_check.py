#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
    "docker logs stokr-api 2>&1 | grep -E 'Started Stokr|cds_backfill|token_refresh_skipped|CdsCurrency' | tail -40",
    "docker exec stokr-api env | grep -i CDS",
    "grep CDS_BACKFILL /opt/stokr/stokr-platform/.env 2>/dev/null || true",
    "docker inspect stokr-api --format '{{.State.StartedAt}}'",
]
for cmd in cmds:
    print("\n===", cmd[:70], "===\n")
    _, o, e = c.exec_command(cmd, timeout=120)
    print((o.read() + e.read()).decode())
c.close()
