#!/usr/bin/env python3
import os, paramiko
PW = os.environ.get("STOKR_PROD_SSH_PASS", "Temp1234..")
ssh = paramiko.SSHClient(); ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy()); ssh.connect("173.249.55.84", username="root", password=PW, timeout=30)

def run(cmd):
    _, o, e = ssh.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

print(run("grep -n CORS /opt/stokr/stokr-platform/docker-compose.yml /opt/stokr/stokr-platform/.env"))
print(run("cd /opt/stokr/stokr-platform && docker compose config 2>/dev/null | grep -A1 CORS || true"))
# force recreate api with env file
print(run("cd /opt/stokr/stokr-platform && docker compose up -d --force-recreate api"))
print(run("sleep 12 && docker exec stokr-api printenv STOKR_CORS_ALLOWED_ORIGINS"))
ssh.close()
