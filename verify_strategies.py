import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

i, o, e = s.exec_command(
    "curl -s http://localhost:8081/api/admin/strategies 2>&1 | python3 -m json.tool 2>/dev/null | head -60",
    get_pty=True)
time.sleep(3)
print(o.read(4096).decode('utf-8', errors='replace'))

s.close()
