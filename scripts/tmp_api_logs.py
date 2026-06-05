#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
    "docker logs stokr-api 2>&1 | tail -80",
    "docker logs stokr-api 2>&1 | grep -F 'Caused by:' | tail -15",
]
for cmd in cmds:
    print("===", cmd, "===")
    _, o, e = c.exec_command(cmd, timeout=60)
    print((o.read() + e.read()).decode("utf-8", "replace"))
c.close()
