import paramiko, select, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

print("=== Maven Build ===")
i, o, e = s.exec_command(
    "cd /root/stokr-platform/stokr-lite/backend && mvn package -DskipTests -q 2>&1",
    get_pty=True)

buf = ""
while not o.channel.exit_status_ready():
    if o.channel.recv_ready():
        d = o.channel.recv(4096).decode('utf-8', errors='replace')
        buf += d; sys.stdout.write(d); sys.stdout.flush()
    time.sleep(0.5)
while o.channel.recv_ready():
    d = o.channel.recv(4096).decode('utf-8', errors='replace')
    buf += d; sys.stdout.write(d)

ec = o.channel.recv_exit_status()
if ec == 0:
    print("\nBUILD OK!")
    # Docker
    i2,o2,e2 = s.exec_command("cd /root/stokr-platform/stokr-lite && docker compose up -d --build backend 2>&1 | tail -8", get_pty=True)
    time.sleep(3)
    while o2.channel.recv_ready():
        print(o2.channel.recv(4096).decode('utf-8', errors='replace'))
else:
    print(f"\nFAILED. Getting errors...")
    i3,o3,e3 = s.exec_command(
        "cd /root/stokr-platform/stokr-lite/backend && mvn compile 2>&1 | grep -E 'ERROR' | head -20",
        get_pty=True)
    time.sleep(3)
    while o3.channel.recv_ready():
        print(o3.channel.recv(4096).decode('utf-8', errors='replace'))

s.close()
