import paramiko, select, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

sftp = s.open_sftp()
sftp.put(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\inverse_bt.py", "/tmp/inverse_bt.py")
sftp.close()

i, o, e = s.exec_command("su - postgres -c 'python3 /tmp/inverse_bt.py' 2>&1", get_pty=True)
while not o.channel.exit_status_ready():
    if o.channel.recv_ready():
        d = o.channel.recv(4096).decode('utf-8', errors='replace')
        if d.strip(): sys.stdout.write(d); sys.stdout.flush()
    time.sleep(0.3)
while o.channel.recv_ready():
    d = o.channel.recv(4096).decode('utf-8', errors='replace')
    if d.strip(): print(d.strip())
s.close()
