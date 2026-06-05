#!/usr/bin/env python3
"""Emergency rollback prod API to last known-good commit."""
import paramiko, time
HOST, BASE = "173.249.55.84", "/opt/stokr/stokr-platform"
GOOD = "af8814f"
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", password="Temp1234..", timeout=30)

def run(cmd, t=1800):
    print(f"\n$ {cmd}\n", flush=True)
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode(errors="replace")
    code = o.channel.recv_exit_status()
    print(out[-2500:], flush=True)
    print(f"exit={code}", flush=True)
    return code

run(f"cd {BASE} && git fetch origin && git reset --hard {GOOD}")
run(f"cd {BASE} && bash deploy.sh api", t=1800)
print("Waiting 150s...", flush=True)
time.sleep(150)
run("curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health")
run('docker ps --filter name=stokr-api --format "{{.Names}} {{.Status}}"')
run(f"cd {BASE} && bash deploy.sh ui", t=1200)
c.close()
print("ROLLBACK DONE", flush=True)
