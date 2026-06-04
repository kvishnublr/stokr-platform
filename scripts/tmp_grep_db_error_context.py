#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command(
    "docker logs stokr-api 2>&1 | grep -A25 'Database access error' | tail -60",
    timeout=120,
)
print((o.read() + e.read()).decode())
c.close()
