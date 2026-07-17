import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

# Give it time and retry
for attempt in range(3):
    time.sleep(3)
    i, o, e = s.exec_command("curl -sw '%{http_code}' http://localhost:8082 -o /dev/null", get_pty=True)
    time.sleep(1)
    code = o.read().decode().strip()
    print(f"Attempt {attempt+1}: HTTP {code}")
    if code == '200': break

# Logs
i2, o2, e2 = s.exec_command("docker logs stokr-lite-frontend --tail 15", get_pty=True)
time.sleep(1)
print("\nFrontend logs:", o2.read().decode())

s.close()
