import paramiko

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=30)

REMOTE = "/root/stokr-platform/stokr-lite/backend/src/main"
BASE = r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\backend\src\main"

# Files that differ between local and server
files = [
    (BASE + r"\java\com\stokr\strategy\Strategy.java",
     REMOTE + "/java/com/stokr/strategy/Strategy.java"),
    (BASE + r"\java\com\stokr\engine\SignalRepository.java",
     REMOTE + "/java/com/stokr/engine/SignalRepository.java"),
    (BASE + r"\java\com\stokr\marketdata\Candle.java",
     REMOTE + "/java/com/stokr/marketdata/Candle.java"),
    (BASE + r"\java\com\stokr\strategy\StrategyPlugin.java",
     REMOTE + "/java/com/stokr/strategy/StrategyPlugin.java"),
    (BASE + r"\java\com\stokr\strategy\MarketContext.java",
     REMOTE + "/java/com/stokr/strategy/MarketContext.java"),
    (BASE + r"\java\com\stokr\strategy\StrategyParams.java",
     REMOTE + "/java/com/stokr/strategy/StrategyParams.java"),
]

sftp = s.open_sftp()
for local, remote in files:
    try:
        sftp.put(local, remote)
        print(f"OK: {local.rsplit(chr(92),1)[-1]}")
    except Exception as e:
        print(f"FAIL: {local.rsplit(chr(92),1)[-1]}: {e}")
sftp.close()
s.close()
print("Sync done")
