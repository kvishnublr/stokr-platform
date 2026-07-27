import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()
print("=== Full backend error ===")
print(c("docker logs stokr-lite-backend 2>&1 | grep -A5 'Caused by\|Error\|Exception\|PSQLException\|Connection' | tail -30"))
print()
print("=== DB URL in compose ===")
print(c("grep SPRING_DATASOURCE_URL /root/stokr-platform/stokr-lite/docker-compose.yml"))
print()
print("=== PostgreSQL status ===")
print(c("systemctl status postgresql 2>/dev/null | head -5 || ps aux | grep postgres | head -3"))
s.close()

