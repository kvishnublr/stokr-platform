import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

sftp = s.open_sftp()
sftp.put(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\verify_seed.py", "/tmp/verify_seed.py")
sftp.close()

stdin, stdout, stderr = s.exec_command("su - postgres -c 'python3 /tmp/verify_seed.py'", get_pty=True)
time.sleep(3)
print(stdout.read().decode())

s.close()
