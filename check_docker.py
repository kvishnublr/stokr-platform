import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=10)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

print("Docker daemon:", c("docker info 2>&1 | head -2"))
print("Docker ps:", c("docker ps -a 2>&1 | head -8"))
print("Docker compose:", c("cd /root/stokr-platform/stokr-lite && docker compose up -d 2>&1 | tail -10"))
s.close()

