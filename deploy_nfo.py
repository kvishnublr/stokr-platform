import paramiko, os

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=15)

sftp = s.open_sftp()

# Upload strategy + resolver
files = {
    r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\backend\src\main\java\com\stokr\strategy\NiftyCalendarSpreadStrategy.java":
        "/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy/NiftyCalendarSpreadStrategy.java",
    r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\backend\src\main\java\com\stokr\broker\NfoOptionResolver.java":
        "/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/broker/NfoOptionResolver.java",
    r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\backend\src\main\resources\db\migration\V36__seed_nifty_calendar_spread.sql":
        "/root/stokr-platform/stokr-lite/backend/src/main/resources/db/migration/V36__seed_nifty_calendar_spread.sql",
}

for local, remote in files.items():
    try:
        os.makedirs(os.path.dirname(remote.replace('/', '\\')), exist_ok=True)
    except:
        pass
    try:
        sftp.put(local, remote)
        print(f"OK: {os.path.basename(local)}")
    except Exception as e:
        print(f"FAIL: {os.path.basename(local)}: {e}")

sftp.close()

# Build
import select, time, sys
i, o, e = s.exec_command(
    "cd /root/stokr-platform/stokr-lite && "
    "mvn package -pl backend -DskipTests -q 2>&1 | tail -20",
    get_pty=True)

exit_code = None
while not o.channel.exit_status_ready():
    if o.channel.recv_ready():
        d = o.channel.recv(4096).decode('utf-8', errors='replace')
        if d.strip(): sys.stdout.write(d); sys.stdout.flush()
    time.sleep(0.3)
while o.channel.recv_ready():
    d = o.channel.recv(4096).decode('utf-8', errors='replace')
    if d.strip(): sys.stdout.write(d)
exit_code = o.channel.recv_exit_status()
print(f"\nBuild exit: {exit_code}")

# Docker rebuild if build OK
if exit_code == 0:
    print("Rebuilding Docker...")
    i2, o2, e2 = s.exec_command(
        "cd /root/stokr-platform/stokr-lite && docker compose up -d --build backend 2>&1 | tail -10",
        get_pty=True)
    while not o2.channel.exit_status_ready():
        if o2.channel.recv_ready():
            sys.stdout.write(o2.channel.recv(4096).decode('utf-8', errors='replace'))
        time.sleep(0.3)
    print("Docker done")

s.close()
