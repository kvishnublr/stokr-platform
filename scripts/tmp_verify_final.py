import paramiko, time, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
time.sleep(60)
cmds = [
    "curl -sf http://127.0.0.1:8080/actuator/health | head -c 100",
    "curl -sf https://stokr.in/api/actuator/health | head -c 100",
    "curl -sf -o /dev/null -w '%{http_code}' https://stokr.in/login",
    "docker ps --format '{{.Names}}|{{.Status}}' | grep -E 'stokr-api|stokr-ui|stokr-postgres'",
    "docker logs stokr-api --since 3m 2>&1 | grep catalog.scan.cycle_done | tail -1",
    "docker logs stokr-api --since 3m 2>&1 | grep platform.ws.first_tick | tail -1",
]
for cmd in cmds:
    _, o, e = c.exec_command(cmd)
    print(cmd, "=>", (o.read()+e.read()).decode('utf-8','replace').strip())
c.close()
