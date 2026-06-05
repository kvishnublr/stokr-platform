#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

cmds = [
    "cd /opt/stokr/stokr-platform && git log -1 --oneline",
    "ls /opt/stokr/stokr-platform/stokr-bootstrap/src/main/resources/db/migration/V98* /opt/stokr/stokr-platform/stokr-bootstrap/src/main/resources/db/migration/V99* 2>&1",
    """docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT version, description, success, installed_rank FROM flyway_schema_history WHERE version >= '95' OR success = false ORDER BY installed_rank DESC LIMIT 15;" """,
    "docker logs stokr-api 2>&1 | grep -i 'Flyway' | tail -8",
]
for cmd in cmds:
    print("===", cmd[:70], "===")
    _, o, e = c.exec_command(cmd, timeout=60)
    print((o.read() + e.read()).decode(errors="replace"))
c.close()
