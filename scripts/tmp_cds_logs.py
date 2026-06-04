#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
    "docker logs stokr-api 2>&1 | grep -i CdsCurrency | tail -30",
    "docker logs stokr-api 2>&1 | grep -i cds_ | tail -30",
    "date -u; TZ=Asia/Kolkata date",
]
for cmd in cmds:
    print("\n===", cmd, "===\n")
    _, o, e = c.exec_command(cmd, timeout=60)
    print((o.read() + e.read()).decode("utf-8", "replace"))
c.close()
