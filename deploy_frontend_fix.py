import paramiko, select, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

# Upload fixed Strategies.jsx
sftp = s.open_sftp()
sftp.put(
    r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\frontend\src\pages\Strategies.jsx",
    "/root/stokr-platform/stokr-lite/frontend/src/pages/Strategies.jsx"
)
sftp.close()
print("Uploaded Strategies.jsx")

# Rebuild frontend
print("\n=== Rebuilding Frontend ===")
i, o, e = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose build frontend 2>&1 | tail -15",
    get_pty=True)
while not o.channel.exit_status_ready():
    if o.channel.recv_ready():
        sys.stdout.write(o.channel.recv(4096).decode('utf-8', errors='replace'))
    time.sleep(0.5)
while o.channel.recv_ready():
    sys.stdout.write(o.channel.recv(4096).decode('utf-8', errors='replace'))

print("\n=== Restarting Frontend ===")
i2, o2, e2 = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose up -d --build frontend 2>&1 | tail -10",
    get_pty=True)
while not o2.channel.exit_status_ready():
    if o2.channel.recv_ready():
        sys.stdout.write(o2.channel.recv(4096).decode('utf-8', errors='replace'))
    time.sleep(0.5)

# Verify
time.sleep(3)
i3, o3, e3 = s.exec_command(
    "curl -s -o /dev/null -w '%{http_code}' http://localhost:8082",
    get_pty=True)
time.sleep(1)
print("\nFrontend HTTP:", o3.read().decode().strip())

s.close()
