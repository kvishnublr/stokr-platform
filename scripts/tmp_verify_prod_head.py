#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command(
    "cd /opt/stokr/stokr-platform && git rev-parse --short HEAD && curl -sf http://127.0.0.1:8080/actuator/health | head -c 80",
    timeout=30,
)
print((o.read() + e.read()).decode())
c.close()
