import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
_, o, e = c.exec_command("docker ps -a --filter name=stokr-api --format '{{.Status}}'; docker logs stokr-api 2>&1 | grep -iE 'safe_startup|Started Stokr' | tail -8", timeout=60)
print((o.read()+e.read()).decode())
c.close()
