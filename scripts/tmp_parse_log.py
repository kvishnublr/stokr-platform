#!/usr/bin/env python3
import paramiko, re

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command("docker logs stokr-api 2>&1 | tail -3000", timeout=90)
log = (o.read()+e.read()).decode("utf-8", "replace")

patterns = [
    r"Error creating bean with name 'jpaSharedEM_entityManagerFactory'[^\\n]{0,500}",
    r"Schema-validation: [^\\n]+",
    r"FlywayException[^\\n]{0,300}",
    r"Migration V\d+[^\\n]{0,200}",
    r"Unable to connect to [^\\n]+",
    r"password authentication failed[^\\n]+",
    r"BeanCreationException: Error creating bean with name 'entityManagerFactory'[^\\n]{0,500}",
]
for p in patterns:
    m = re.findall(p, log, re.I)
    if m:
        print("PATTERN", p[:40])
        for x in m[-3:]:
            print(x[:400])
        print()

# also print last Started or last Caused by with schema
idx = log.rfind("Caused by:")
if idx >= 0:
    print("LAST CAUSED BY SNIPPET:")
    print(log[idx:idx+2000][:2000])
c.close()
