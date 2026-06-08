import paramiko, json
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
payload = json.dumps({"email":"admin@stokr.local","password":"admin123"})
cmd = f"""curl -s -w '\\nHTTP:%{{http_code}}' -X POST https://stokr.in/api/auth/login -H 'Content-Type: application/json' -d '{payload}' | tail -c 400"""
_, o, e = c.exec_command(cmd)
print((o.read()+e.read()).decode())
_, o, e = c.exec_command("curl -sf http://127.0.0.1:8080/actuator/health | head -c 80")
print("local health:", (o.read()+e.read()).decode())
_, o, e = c.exec_command("docker logs stokr-api --since 2m 2>&1 | grep catalog.scan.cycle_done | tail -1")
print("scan:", (o.read()+e.read()).decode())
c.close()
