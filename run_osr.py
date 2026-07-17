import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

sftp = s.open_sftp()
sftp.put(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\oversold_report.py", "/tmp/osr.py")
sftp.close()

print("Running Oversold Bounce NIFTY 100 report...")
i, o, e = s.exec_command("su - postgres -c 'python3 /tmp/osr.py' > /tmp/osr_out.txt 2>&1; echo DONE:$?", get_pty=True)

import select
while not o.channel.exit_status_ready():
    time.sleep(2)

# Check if done
i2, o2, e2 = s.exec_command("wc -l /tmp/osr_out.txt; echo '---LAST---'; tail -30 /tmp/osr_out.txt", get_pty=True)
time.sleep(2)
out = o2.read().decode()
print(out)
s.close()
