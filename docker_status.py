import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

i, o, e = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose ps 2>&1 && "
    "echo '---' && "
    "docker compose logs backend --tail 5 2>&1",
    get_pty=True)
time.sleep(3)
while o.channel.recv_ready():
    print(o.channel.recv(4096).decode('utf-8', errors='replace'))
s.close()
