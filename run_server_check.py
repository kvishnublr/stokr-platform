import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

sftp = s.open_sftp()
sftp.put(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\server_check.py", "/root/server_check.py")
sftp.close()

i, o, e = s.exec_command("python3 /root/server_check.py", get_pty=True)
time.sleep(5)
print(o.read().decode())
s.close()
