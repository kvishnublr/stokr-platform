import paramiko, select, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

# Check compose file
print("=== Compose File ===")
i, o, e = s.exec_command("cd /root/stokr-platform/stokr-lite && docker compose config --services 2>&1", get_pty=True)
time.sleep(2)
while o.channel.recv_ready():
    print(o.channel.recv(4096).decode('utf-8', errors='replace'))

# Start all services
print("\n=== Starting All Services ===")
i2, o2, e2 = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose up -d --build 2>&1 | tail -20",
    get_pty=True)
while not o2.channel.exit_status_ready():
    if o2.channel.recv_ready():
        d = o2.channel.recv(4096).decode('utf-8', errors='replace')
        sys.stdout.write(d); sys.stdout.flush()
    time.sleep(0.5)
while o2.channel.recv_ready():
    sys.stdout.write(o2.channel.recv(4096).decode('utf-8', errors='replace'))

# Verify
print("\n=== Final Status ===")
i3, o3, e3 = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose ps 2>&1 && echo '---' && docker compose logs --tail 10 2>&1",
    get_pty=True)
time.sleep(3)
while o3.channel.recv_ready():
    print(o3.channel.recv(4096).decode('utf-8', errors='replace'))

s.close()
