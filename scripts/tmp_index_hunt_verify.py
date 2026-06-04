#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
    "docker exec stokr-postgres psql -U postgres -d stokr_platform -c \"SELECT version, success FROM flyway_schema_history WHERE version='87';\"",
    "docker exec stokr-redis redis-cli GET stokr:strategy:INDEX_HUNT:enabled",
    "docker exec stokr-api printenv | grep INDEX_HUNT || true",
    "docker logs stokr-api 2>&1 | grep INDEX_HUNT | grep -E 'catalog.scan.signal|skip_broker|both_mode|fanout' | tail -10",
]
for cmd in cmds:
    print("$", cmd[:90])
    _, o, e = c.exec_command(cmd, timeout=90)
    print((o.read() + e.read()).decode())
c.close()
