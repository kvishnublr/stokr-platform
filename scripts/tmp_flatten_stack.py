#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command("docker logs stokr-api 2>&1 | grep -A5 '7747c58f' | head -30", timeout=120)
print((o.read()+e.read()).decode())
_, o, e = c.exec_command("docker logs stokr-api 2>&1 | grep -iE 'UnexpectedRollback|rollback-only|flatten failed' | tail -20", timeout=120)
print((o.read()+e.read()).decode())
_, o, e = c.exec_command("cd /opt/stokr/stokr-platform && git log -1 --oneline", timeout=30)
print("DEPLOYED:", (o.read()+e.read()).decode())
c.close()
