#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmd = "docker logs stokr-api 2>&1 | grep -iE 'Flyway|Schema|BeanCreation|DuplicateMapping|entityManager|Migration' | tail -40"
_, o, e = c.exec_command(cmd, timeout=90)
print(o.read().decode(errors="replace"))
c.close()
