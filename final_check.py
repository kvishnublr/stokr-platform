import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

# Check docker
i, o, e = s.exec_command("docker ps --format '{{.Names}} {{.Status}} {{.Ports}}'", get_pty=True)
time.sleep(2)
out = o.read(4096).decode('utf-8', errors='replace')
print("Containers:\n" + out)

# If no backend, start it
if 'stokr-lite-backend' not in out:
    print("\nBackend missing - starting...")
    s.exec_command("cd /root/stokr-platform/stokr-lite && docker compose up -d backend", get_pty=True)
    time.sleep(8)
    i2, o2, e2 = s.exec_command("docker ps --format '{{.Names}} {{.Status}}'", get_pty=True)
    time.sleep(1)
    print(o2.read(4096).decode('utf-8', errors='replace'))

# Wait and check API
print("\nWaiting for backend to boot...")
time.sleep(15)
i3, o3, e3 = s.exec_command("curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health 2>&1", get_pty=True)
time.sleep(2)
print("API health: HTTP " + o3.read(4096).decode('utf-8', errors='replace').strip())

s.close()
