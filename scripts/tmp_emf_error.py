#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command("docker logs stokr-api 2>&1 | grep -A3 'entityManagerFactory\\|BeanCreationException\\|FlywayException' | tail -40", timeout=60)
print((o.read()+e.read()).decode()[:8000])
c.close()
