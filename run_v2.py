import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode('utf-8',errors='replace').strip()

# Run the script and stream output
cmd = "python3 -u /root/backfill_v2.py 2>&1"
stdin,stdout,stderr = s.exec_command(cmd)

# Read in chunks with timeout
import select, sys
deadline = time.time() + 600
while time.time() < deadline:
    if stdout.channel.recv_ready():
        data = stdout.channel.recv(4096).decode('utf-8',errors='replace')
        if data.strip():
            sys.stdout.write(data)
            sys.stdout.flush()
    if stderr.channel.recv_stderr_ready():
        data = stderr.channel.recv_stderr(4096).decode('utf-8',errors='replace')
        if data.strip():
            sys.stderr.write(data)
            sys.stderr.flush()
    if stdout.channel.exit_status_ready():
        break
    time.sleep(0.5)

# Get any remaining
while stdout.channel.recv_ready():
    d = stdout.channel.recv(4096).decode('utf-8',errors='replace')
    if d.strip(): print(d.strip())
print(f"\nExit: {stdout.channel.recv_exit_status()}")
s.close()

