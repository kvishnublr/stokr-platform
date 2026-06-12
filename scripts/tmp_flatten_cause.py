#!/usr/bin/env python3
import paramiko, re
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command("docker logs stokr-api 2>&1 | grep '7747c58f' | grep -iE 'Exception|Error|failed|Caused|NullPointer|Illegal' | head -20", timeout=120)
raw = (o.read()+e.read()).decode()
print(raw)
# get full error line with stack root
_, o, e = c.exec_command("docker logs stokr-api 2>&1 | grep '7747c58f' | grep 'level\":\"ERROR' | head -3", timeout=120)
print((o.read()+e.read()).decode()[:4000])
c.close()
