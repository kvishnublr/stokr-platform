#!/usr/bin/env python3
import paramiko
import time

HOST = "173.249.55.84"
PWD = "Temp1234.."
MAIN = "/opt/stokr/stokr-platform"

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password=PWD, timeout=30)


def run(cmd, timeout=600):
    print(f"\n>>> {cmd}\n", flush=True)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    code = o.channel.recv_exit_status()
    tail = out[-12000:] if len(out) > 12000 else out
    print(tail, flush=True)
    print(f"exit={code}", flush=True)
    return code, out


run("docker ps -a --format '{{.Names}}|{{.Status}}|{{.Networks}}' | grep stokr")
run(f"cd {MAIN} && docker compose ps -a 2>/dev/null || docker-compose ps -a")
run("docker network ls | grep stokr")
run("docker inspect stokr-api --format '{{json .NetworkSettings.Networks}}' 2>/dev/null")
run("docker inspect stokr-postgres --format '{{json .NetworkSettings.Networks}}' 2>/dev/null")
run("docker logs stokr-postgres --tail 20 2>&1")
run("docker logs stokr-api --tail 5 2>&1")

c.close()
