#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cid = "7747c58f"
_, o, e = c.exec_command(f"docker logs stokr-api 2>&1 | grep '{cid}' | grep -v 'gate_near_miss\\|platform.ws\\|reconciliation.discrepancy\\|external_exit_pending' | head -60", timeout=120)
print((o.read()+e.read()).decode())
c.close()
