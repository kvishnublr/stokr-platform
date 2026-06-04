#!/usr/bin/env python3
import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, t=180):
    _, o, e = c.exec_command(cmd, timeout=t)
    return (o.read() + e.read()).decode("utf-8", "replace").strip()

print("SPRING_PROFILES_ACTIVE:", run("docker exec stokr-api sh -c 'echo ${SPRING_PROFILES_ACTIVE:-<unset>}'"))
print("Started:", run("docker inspect stokr-api --format '{{.State.StartedAt}}'"))
total = run("docker logs stokr-api 2>&1 | wc -l")
print("Total lines since start:", total)
for pat in ["catalog.scan.binding_done", "platform.ws.binary", "platform.ws.text_received", "execution_audit", "order.intent", "platform.recovery"]:
    cnt = run(f"docker logs stokr-api 2>&1 | grep -c '{pat}' || true")
    print(f"  {pat}: {cnt}")
c.close()
