#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command("docker logs stokr-api 2>&1 | grep '168a6574' | grep -v 'platform.ws\\|gate_near' | head -40", timeout=120)
print((o.read()+e.read()).decode())
_, o, e = c.exec_command("docker logs stokr-api --since 5m 2>&1 | grep -iE 'BadRequest|market.hours|BROKER_TRUTH|execution.guard|168a6574' | tail -30", timeout=120)
print((o.read()+e.read()).decode())
c.close()
