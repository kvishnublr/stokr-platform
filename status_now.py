import paramiko;s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy());s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=10)
def c(cmd): i,o,e=s.exec_command(cmd); return o.read().decode(errors='replace').strip()
print("Health:", c("curl -s -m3 http://localhost:8080/actuator/health 2>/dev/null || echo 'DOWN'"))
print("Java:", c("ps aux | grep 'stokr-lite' | grep -v grep | head -1"))
print("JAR:", c("ls -la /root/stokr-lite.jar 2>/dev/null || echo 'no jar'"))
print("Log:", c("tail -3 /var/log/stokr.log 2>/dev/null || echo 'no log'"))
# If down, try docker
print("Docker:", c("docker ps --format '{{.Names}} {{.Status}}' 2>&1 | grep stokr | head -3"))
s.close()

