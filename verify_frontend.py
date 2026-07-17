import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

time.sleep(5)
i, o, e = s.exec_command(
    "curl -s http://localhost:8082 2>&1 | head -5; echo '---STATUS:'; curl -s -o /dev/null -w '%{http_code}' http://localhost:8082",
    get_pty=True)
time.sleep(3)
print(o.read().decode())

s.close()
