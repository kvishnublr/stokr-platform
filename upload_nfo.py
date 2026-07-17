import paramiko

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)
sftp = s.open_sftp()

base = r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\backend\src\main"

files = [
    (base + r"\java\com\stokr\strategy\NiftyCalendarSpreadStrategy.java",
     "/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy/NiftyCalendarSpreadStrategy.java"),
    (base + r"\java\com\stokr\broker\NfoOptionResolver.java",
     "/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/broker/NfoOptionResolver.java"),
    (base + r"\resources\db\migration\V36__seed_nifty_calendar_spread.sql",
     "/root/stokr-platform/stokr-lite/backend/src/main/resources/db/migration/V36__seed_nifty_calendar_spread.sql"),
]

for local, remote in files:
    try:
        sftp.put(local, remote)
        print(f"OK: {local.split(chr(92))[-1]}")
    except Exception as e:
        print(f"FAIL: {local.split(chr(92))[-1]}: {e}")

sftp.close()
s.close()
print("Done")
