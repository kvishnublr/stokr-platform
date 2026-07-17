import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

# 1. API raw response
i, o, e = s.exec_command('curl -s http://localhost:8081/api/strategies', get_pty=True)
time.sleep(3)
raw = o.read().decode()
print("API length:", len(raw))
print(raw[:2000])

s.close()
