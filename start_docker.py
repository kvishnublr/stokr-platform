import paramiko, select, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

print("=== Starting Docker ===")
i, o, e = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose up -d --build backend 2>&1 | tail -15",
    get_pty=True)
while not o.channel.exit_status_ready():
    if o.channel.recv_ready():
        sys.stdout.write(o.channel.recv(4096).decode('utf-8', errors='replace'))
    time.sleep(0.5)
while o.channel.recv_ready():
    sys.stdout.write(o.channel.recv(4096).decode('utf-8', errors='replace'))

print("\n=== Status ===")
i2, o2, e2 = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose ps 2>&1", get_pty=True)
time.sleep(2)
while o2.channel.recv_ready():
    print(o2.channel.recv(4096).decode('utf-8', errors='replace'))

print("\n=== Backend logs ===")
i3, o3, e3 = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose logs backend --tail 10 2>&1", get_pty=True)
time.sleep(2)
while o3.channel.recv_ready():
    print(o3.channel.recv(4096).decode('utf-8', errors='replace'))

s.close()
