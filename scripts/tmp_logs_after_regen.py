#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command(
    "docker logs stokr-api 2>&1 | awk '/10:47:57/,/10:48:30/' | grep -iE 'error|exception|rollback|failed|reject|execution\\.' | head -40",
    timeout=120,
)
print((o.read() + e.read()).decode())
c.close()
