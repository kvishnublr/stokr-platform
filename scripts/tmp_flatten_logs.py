#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command("docker logs stokr-api --since 5m 2>&1 | grep -iE '7747c58f|flatten|TERMINAL_FLATTEN|BrokerPosition|suppressAuto|rollback|Exception' | tail -60", timeout=60)
print((o.read()+e.read()).decode())
_, o, e = c.exec_command("docker logs stokr-api --since 5m 2>&1 | tail -80", timeout=60)
print((o.read()+e.read()).decode())
c.close()
