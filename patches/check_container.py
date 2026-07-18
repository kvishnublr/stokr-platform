import subprocess
r = subprocess.run(['ssh', 'root@173.249.55.84', 'docker exec stokr-lite-backend grep -c "getDepthPrice" /app/classes/com/stokr/arbitrage/OptionChainService.class 2>/dev/null || echo NOT_FOUND; docker exec stokr-lite-backend sh -c "ls -la /app/" 2>/dev/null'], capture_output=True, text=True, timeout=30)
print(r.stdout)
print(r.stderr)
