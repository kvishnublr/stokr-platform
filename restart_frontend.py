import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

# Restart
print("Restarting frontend container...")
s.exec_command('cd /root/stokr-platform/stokr-lite && docker compose up -d frontend', get_pty=True)
time.sleep(8)

# Check
import subprocess
for cmd in [
    'docker ps --format "{{.Names}} {{.Status}}"',
    'curl -s -o /dev/null -w "%{http_code}" http://localhost:8082'
]:
    i, o, e = s.exec_command(cmd, get_pty=True)
    time.sleep(2)
    print(o.read().decode().strip())

s.close()
