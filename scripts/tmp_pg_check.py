#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, timeout=60):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(f"\n$ {cmd}\n{out.strip()[:4000]}\n")

run("docker logs stokr-api 2>&1 | grep -iE 'flyway|entityManager|SQLException|Migration' | tail -20")
run("""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT count(*) FROM auth_users;" """)
run("docker volume ls | grep postgres")
run("docker inspect stokr-postgres --format '{{range .Mounts}}{{.Name}} {{end}}'")
c.close()
