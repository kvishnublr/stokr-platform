import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

print("=== Build error ===")
print(c("cd /root/stokr-platform/stokr-lite && docker compose build backend 2>&1 | grep -i 'error\|failed\|cannot find' | head -20"))

print("\n=== Try maven build directly ===")
print(c("cd /root/stokr-platform/stokr-lite/backend && mvn compile -DskipTests 2>&1 | grep ERROR | head -10"))
s.close()

