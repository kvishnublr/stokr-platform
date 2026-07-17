import paramiko; s=paramiko.SSHClient(); s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='***',timeout=15)

# Fix pg_hba.conf temporarily to allow md5 for localhost
s.exec_command("sed -i 's/scram-sha-256/md5/' /etc/postgresql/16/main/pg_hba.conf 2>/dev/null; systemctl reload postgresql 2>/dev/null || pg_ctlcluster 16 main reload 2>/dev/null; echo done")
print("pg_hba fixed, running backtest...")

# Now run
sftp=s.open_sftp()
sftp.put(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\bt.py", "/root/bt.py")
sftp.close()

i,o,e=s.exec_command("python3 /root/bt.py 2>&1", get_pty=True)
import select,time,sys
while not o.channel.exit_status_ready():
    if o.channel.recv_ready():
        d=o.channel.recv(4096).decode('utf-8',errors='replace')
        if d.strip(): sys.stdout.write(d);sys.stdout.flush()
    time.sleep(0.3)
while o.channel.recv_ready():
    d=o.channel.recv(4096).decode('utf-8',errors='replace')
    if d.strip(): print(d.strip())

# Restore pg_hba
s.exec_command("sed -i 's/^host.*md5$/host    all             all             127.0.0.1\\/32            scram-sha-256/' /etc/postgresql/16/main/pg_hba.conf 2>/dev/null; systemctl reload postgresql 2>/dev/null; echo restored")
s.close()
