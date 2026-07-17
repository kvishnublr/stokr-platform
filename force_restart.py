import paramiko, select, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

# Kill old processes holding port 8081
print("=== Killing old port 8081 holder ===")
i, o, e = s.exec_command(
    "fuser -k 8081/tcp 2>/dev/null; "
    "docker ps -a --filter name=stokr-lite --format '{{.Names}}' | xargs -r docker rm -f 2>/dev/null; "
    "echo 'cleaned'", get_pty=True)
time.sleep(2)
while o.channel.recv_ready():
    print(o.channel.recv(4096).decode('utf-8', errors='replace'))

# Start fresh
print("\n=== Restarting ===")
i2, o2, e2 = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose up -d 2>&1", get_pty=True)
time.sleep(5)
while o2.channel.recv_ready():
    print(o2.channel.recv(4096).decode('utf-8', errors='replace'))

# Verify
print("\n=== Status ===")
i3, o3, e3 = s.exec_command(
    "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' 2>&1", get_pty=True)
time.sleep(2)
while o3.channel.recv_ready():
    print(o3.channel.recv(4096).decode('utf-8', errors='replace'))

# Backend logs
print("\n=== Backend Logs ===")
i4, o4, e4 = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && docker compose logs backend --tail 15 2>&1", get_pty=True)
time.sleep(2)
while o4.channel.recv_ready():
    print(o4.channel.recv(4096).decode('utf-8', errors='replace'))

# Quick API check
print("\n=== API Check ===")
i5, o5, e5 = s.exec_command(
    "curl -s http://localhost:8081/actuator/health 2>&1 || echo 'API not responding'", get_pty=True)
time.sleep(3)
while o5.channel.recv_ready():
    print(o5.channel.recv(4096).decode('utf-8', errors='replace'))

s.close()
