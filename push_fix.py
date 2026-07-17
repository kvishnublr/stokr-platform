import paramiko, select, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

sftp = s.open_sftp()
sftp.put(
    r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\frontend\src\pages\Strategies.jsx",
    "/root/stokr-platform/stokr-lite/frontend/src/pages/Strategies.jsx"
)
sftp.close()
print("Uploaded fix")

# Rebuild + restart
print("Rebuilding frontend...")
i, o, e = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose up -d --build frontend 2>&1 | tail -8",
    get_pty=True)
while not o.channel.exit_status_ready():
    if o.channel.recv_ready():
        sys.stdout.write(o.channel.recv(4096).decode('utf-8', errors='replace'))
    time.sleep(0.5)
while o.channel.recv_ready():
    sys.stdout.write(o.channel.recv(4096).decode('utf-8', errors='replace'))

# Verify
time.sleep(5)
i2, o2, e2 = s.exec_command("docker ps --format '{{.Names}} {{.Status}}' && curl -sw ' HTTP:%{http_code}' http://localhost:8082 -o /dev/null", get_pty=True)
time.sleep(2)
print("\n" + o2.read().decode().strip())

s.close()
