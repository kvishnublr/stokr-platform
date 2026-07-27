import paramiko
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)
def c(cmd):
    i,o,e = s.exec_command(cmd)
    return o.read().decode(errors='replace').strip()

print("=== Port check ===")
print(c("ss -tlnp | grep 8080"))

print("\n=== Raw curl with verbose ===")
print(c("curl -v http://localhost:8080/api/backtest/portfolio/model 2>&1 | head -30"))

print("\n=== Try other endpoints ===")
print(c("curl -s http://localhost:8080/api/strategies 2>/dev/null | head -c 300"))
print(c("curl -s http://localhost:8080/api/strategies/enabled 2>/dev/null | head -c 300"))

print("\n=== Docker env vars ===")
print(c("docker exec stokr-lite-backend env | grep SPRING"))

print("\n=== Flyway migration state ===")
print(c("docker logs stokr-lite-backend 2>&1 | grep -i 'flyway\|migration\|V28\|V29\|V30\|V31\|V32\|V33\|version' | head -10"))

print("\n=== DB strategies table ===")
print(c("su - postgres -c \"psql -d stokr_platform -c \\\"SELECT id, name, strategy_type, enabled FROM strategies ORDER BY id LIMIT 10\\\"\" 2>&1"))

print("\n=== Check if QuickFlip is in DB ===")
print(c("su - postgres -c \"psql -d stokr_platform -c \\\"SELECT id, name, strategy_type FROM strategies WHERE strategy_type = 'QUICK_FLIP'\\\"\" 2>&1"))

s.close()

