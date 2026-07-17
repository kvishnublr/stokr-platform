import paramiko, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

# Upload the fixed file
sftp = s.open_sftp()
sftp.put(
    r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\frontend\src\pages\Strategies.jsx",
    "/root/stokr-platform/stokr-lite/frontend/src/pages/Strategies.jsx"
)
sftp.close()

# Verify upload
i, o, e = s.exec_command("grep -c INSIDER_MOMENTUM /root/stokr-platform/stokr-lite/frontend/src/pages/Strategies.jsx", get_pty=True)
time.sleep(1)
count = o.read().decode().strip()
print(f"INSIDER_MOMENTUM count in server file: {count}")

if count == '0':
    print("UPLOAD FAILED - trying again...")
    with open(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\frontend\src\pages\Strategies.jsx", "r") as f:
        content = f.read()
    with sftp.open("/root/stokr-platform/stokr-lite/frontend/src/pages/Strategies.jsx", "w") as rf:
        rf.write(content)

# Clean build frontend (no cache)
print("\n=== Clean rebuild (--no-cache) ===")
cmd = "cd /root/stokr-platform/stokr-lite && docker compose build --no-cache frontend 2>&1 | tail -8"
i2, o2, e2 = s.exec_command(cmd, get_pty=True)

while not o2.channel.exit_status_ready():
    if o2.channel.recv_ready():
        sys.stdout.write(o2.channel.recv(4096).decode('utf-8', errors='replace'))
    time.sleep(0.5)
while o2.channel.recv_ready():
    sys.stdout.write(o2.channel.recv(4096).decode('utf-8', errors='replace'))

# Restart
print("\n\n=== Restarting ===")
s.exec_command("cd /root/stokr-platform/stokr-lite && docker compose up -d frontend", get_pty=True)
time.sleep(5)

# Verify INSIDER in built image
i3, o3, e3 = s.exec_command(
    "docker run --rm stokr-lite-frontend grep INSIDER_MOMENTUM /usr/share/nginx/html/assets/*.js 2>&1 | head -1",
    get_pty=True)
time.sleep(2)
print("\nDocker image contains INSIDER:", o3.read().decode().strip()[:100])

s.close()
