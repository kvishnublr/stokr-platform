#!/usr/bin/env python3
import os, paramiko, re
PW = os.environ.get("STOKR_PROD_SSH_PASS", "Temp1234..")
ssh = paramiko.SSHClient(); ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy()); ssh.connect("173.249.55.84", username="root", password=PW, timeout=30)

def run(cmd):
    _, o, e = ssh.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

env = run("grep -E 'POSTGRES|DATASOURCE|DB_' /opt/stokr/stokr-platform/.env | sed 's/=.*/=***'/")
print(env)
print(run("cd /opt/stokr/stokr-platform && docker compose config 2>/dev/null | grep -E 'POSTGRES_PASSWORD|SPRING_DATASOURCE_PASSWORD' | sed 's/:.*/:***/'"))
# try local socket auth inside postgres container
print(run("docker exec stokr-postgres psql -U postgres -d stokr_platform -c 'SELECT 1' 2>&1"))
ssh.close()
