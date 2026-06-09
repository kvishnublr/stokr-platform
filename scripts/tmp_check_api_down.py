#!/usr/bin/env python3
import os, paramiko
PW = os.environ.get("STOKR_PROD_SSH_PASS", "Temp1234..")
ssh = paramiko.SSHClient(); ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy()); ssh.connect("173.249.55.84", username="root", password=PW, timeout=30)

def run(cmd):
    _, o, e = ssh.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

print(run("cd /opt/stokr/stokr-platform && docker compose ps"))
print("\n=== API logs tail ===")
print(run("docker logs stokr-api --tail 80 2>&1"))
print("\n=== postgres logs tail ===")
print(run("docker logs stokr-postgres --tail 30 2>&1"))
ssh.close()
