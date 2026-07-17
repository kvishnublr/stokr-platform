import paramiko

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)
sftp = s.open_sftp()

# Sync MomentumSurgeStrategy (was broken on server)
local = r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\backend\src\main\java\com\stokr\strategy\MomentumSurgeStrategy.java"
remote = "/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy/MomentumSurgeStrategy.java"
sftp.put(local, remote)
print("OK: MomentumSurgeStrategy.java (fixed)")

# Also sync all strategy files that were previously deployed to ensure consistency
for f in ["QuickFlipStrategy.java", "BtstStrategy.java", "TwentyDayBreakoutStrategy.java",
          "InstitutionalFootprintStrategy.java"]:
    lf = rf"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\backend\src\main\java\com\stokr\strategy\{f}"
    rf = f"/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy/{f}"
    try:
        sftp.put(lf, rf)
        print(f"OK: {f}")
    except Exception as e:
        print(f"SKIP: {f} ({e})")

sftp.close()
s.close()
print("Sync done")
