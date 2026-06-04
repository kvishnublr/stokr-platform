import paramiko, json, time
time.sleep(70)
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command('curl -sf -m 20 -X POST http://127.0.0.1:8080/api/auth/login -H "Content-Type: application/json" -d \'{"principal":"admin","password":"password"}\'', timeout=60)
tok = json.loads(o.read().decode())["data"]["accessToken"]
_, o, e = c.exec_command('curl -sf -m 25 -H "Authorization: Bearer %s" "http://127.0.0.1:8080/api/admin/operations/diagnostics"' % tok, timeout=60)
d = json.loads(o.read().decode())["data"]
print("safeStartup", d["safeStartup"])
c.close()
