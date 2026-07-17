import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)

script = r"""
# Kill EVERYTHING on port 8080
fuser -k 8080/tcp 2>/dev/null
sleep 2

# Remove all stokr containers
docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null

# Start fresh
cd /root/stokr-platform/stokr-lite && docker compose up -d 2>&1 | tail -3
sleep 40

echo "=== Status ==="
docker ps --format '{{.Names}} {{.Status}}' | grep stokr

echo "=== Port 8080 ==="
ss -tlnp | grep 8080

echo "=== Logs ==="
docker logs stokr-lite-backend 2>&1 | grep -E 'Started Application|Error|Flyway|migrated|8080|WARN.*schema|Exception|JVM.*in ' | tail -10

echo "=== Health ==="
curl -s http://localhost:8080/actuator/health

echo ""
echo "=== Portfolio API ==="
sleep 5
curl -s http://localhost:8080/api/backtest/portfolio/model | head -c 600
"""
stdin,stdout,stderr = s.exec_command(script)
print(stdout.read().decode(errors='replace')[-4000:])
er = stderr.read().decode(errors='replace')
if er.strip(): print(f"STDERR: {er[-300:]}")
s.close()
