import paramiko, select, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

REMOTE = "/root/stokr-platform/stokr-lite/backend/src/main"
BASE = r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\backend\src\main"

files = [
    (BASE + r"\java\com\stokr\strategy\InsiderMomentumStrategy.java",
     REMOTE + "/java/com/stokr/strategy/InsiderMomentumStrategy.java"),
    (BASE + r"\java\com\stokr\strategy\NiftyCalendarSpreadStrategy.java",
     REMOTE + "/java/com/stokr/strategy/NiftyCalendarSpreadStrategy.java"),
    (BASE + r"\java\com\stokr\broker\NfoOptionResolver.java",
     REMOTE + "/java/com/stokr/broker/NfoOptionResolver.java"),
    (BASE + r"\java\com\stokr\marketdata\InsiderDataScheduler.java",
     REMOTE + "/java/com/stokr/marketdata/InsiderDataScheduler.java"),
    (BASE + r"\java\com\stokr\engine\SignalProcessor.java",
     REMOTE + "/java/com/stokr/engine/SignalProcessor.java"),
    (BASE + r"\resources\db\migration\V37__seed_insider_momentum.sql",
     REMOTE + "/resources/db/migration/V37__seed_insider_momentum.sql"),
]

sftp = s.open_sftp()
for local, remote in files:
    try:
        sftp.put(local, remote)
        print(f"OK: {local.rsplit(chr(92),1)[-1]}")
    except Exception as e:
        print(f"FAIL: {local.rsplit(chr(92),1)[-1]}: {e}")
sftp.close()

# Build
print("\n=== Building (from backend dir) ===")
i, o, e = s.exec_command(
    "cd /root/stokr-platform/stokr-lite/backend && mvn package -DskipTests -q 2>&1",
    get_pty=True)

buf = ""
while not o.channel.exit_status_ready():
    if o.channel.recv_ready():
        d = o.channel.recv(4096).decode('utf-8', errors='replace')
        buf += d; sys.stdout.write(d); sys.stdout.flush()
    time.sleep(0.5)
while o.channel.recv_ready():
    d = o.channel.recv(4096).decode('utf-8', errors='replace')
    buf += d; sys.stdout.write(d)

ec = o.channel.recv_exit_status()
if ec == 0:
    print("\nBUILD OK — deploying Docker...")
    i2, o2, e2 = s.exec_command(
        "cd /root/stokr-platform/stokr-lite && docker compose up -d --build backend 2>&1 | tail -10",
        get_pty=True)
    time.sleep(5)
    while o2.channel.recv_ready():
        print(o2.channel.recv(4096).decode('utf-8', errors='replace'))
else:
    print(f"\nBUILD FAILED (exit={ec})")
    i3, o3, e3 = s.exec_command(
        "cd /root/stokr-platform/stokr-lite/backend && mvn compile 2>&1 | grep -E 'ERROR.*\.java|error:' | head -15",
        get_pty=True)
    time.sleep(3)
    while o3.channel.recv_ready():
        print(o3.channel.recv(4096).decode('utf-8', errors='replace'))

s.close()
