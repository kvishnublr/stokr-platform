import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

sftp = s.open_sftp()
sftp.put(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\seed_db.py", "/tmp/seed_db.py")
sftp.close()

stdin, stdout, stderr = s.exec_command("su - postgres -c 'python3 /tmp/seed_db.py'", get_pty=True)
time.sleep(4)
print(stdout.read().decode())
err = stderr.read().decode().strip()
if err: print("ERR:", err[:300])

# Verify API
stdin2, stdout2, stderr2 = s.exec_command('curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/actuator/health', get_pty=True)
time.sleep(2)
print("\nAPI health:", stdout2.read().decode().strip())

s.close()
