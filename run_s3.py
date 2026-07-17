import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

sftp = s.open_sftp()
sftp.put(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\strategy3.py", "/tmp/s3.py")
sftp.close()

i, o, e = s.exec_command("su - postgres -c 'python3 /tmp/s3.py' > /tmp/s3_out.txt 2>&1; echo DONE", get_pty=True)
import select
while not o.channel.exit_status_ready():
    time.sleep(2)

i2, o2, e2 = s.exec_command("cat /tmp/s3_out.txt", get_pty=True)
time.sleep(2)
print(o2.read().decode())
s.close()
