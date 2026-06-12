#!/usr/bin/env python3
import paramiko
c=paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy()); c.connect('173.249.55.84',username='root',password='Temp1234..',timeout=30)
_,o,e=c.exec_command("docker logs stokr-api --since 5m 2>&1 | grep -i 'NullPointer' | head -5", timeout=120)
raw=(o.read()+e.read()).decode()
print(raw[:6000] if raw else 'no npe lines')
_,o,e=c.exec_command("docker logs stokr-api --since 5m 2>&1 | grep '2df66a9b' | head -20", timeout=120)
print((o.read()+e.read()).decode()[:4000])
c.close()
