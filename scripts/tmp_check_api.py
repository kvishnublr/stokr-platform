#!/usr/bin/env python3
import paramiko
import time

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
time.sleep(60)
_, o, e = c.exec_command(
    "docker ps --filter name=stokr-api; docker logs stokr-api 2>&1 | grep TIME_WINDOW | tail -3",
    timeout=60,
)
print((o.read() + e.read()).decode())
c.close()
