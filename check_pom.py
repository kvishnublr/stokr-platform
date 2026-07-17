import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

i, o, e = s.exec_command(
    "cat /root/stokr-platform/stokr-lite/pom.xml | grep -A2 '<module' 2>/dev/null; "
    "echo '---'; "
    "ls /root/stokr-platform/stokr-lite/backend/pom.xml 2>/dev/null && echo 'backend pom exists' || echo 'backend pom missing'; "
    "echo '---'; "
    "cd /root/stokr-platform/stokr-lite && mvn package -DskipTests -q 2>&1 | tail -10",
    get_pty=True)
time.sleep(5)
while o.channel.recv_ready():
    print(o.channel.recv(4096).decode('utf-8', errors='replace'))
time.sleep(3)
while o.channel.recv_ready():
    print(o.channel.recv(4096).decode('utf-8', errors='replace'))
s.close()
