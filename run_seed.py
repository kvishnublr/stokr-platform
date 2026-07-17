import paramiko, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password=sys.argv[1], timeout=20)

sftp = s.open_sftp()
sftp.put(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\seed_db.py", "/tmp/seed_db.py")
sftp.close()

stdin, stdout, stderr = s.exec_command("su - postgres -c 'python3 /tmp/seed_db.py'", get_pty=True)
time.sleep(3)
print(stdout.read().decode())
err = stderr.read().decode().strip()
if err: print("ERR:", err[:200])

s.close()
