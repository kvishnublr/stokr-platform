import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='`$SSH_PASSWORD',timeout=30)

script = r"""
docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null
cd /root/stokr-platform && git pull origin Release_v8
cd /root/stokr-platform/stokr-lite && docker compose up -d --build 2>&1 | tail -5
sleep 35
echo "=== Status ==="
docker ps --format '{{.Names}} {{.Status}}' | grep stokr
echo "=== Logs ==="
docker logs stokr-lite-backend 2>&1 | grep -E 'Started|ERROR|Flyway|migrate|8080|WARN.*schema|Exception|JVM.*started' | tail -10
echo "=== Health ==="
curl -s http://localhost:8080/actuator/health
echo ""
echo "=== Portfolio Model ==="
curl -s http://localhost:8080/api/backtest/portfolio/model | head -c 800
"""
stdin, stdout, stderr = s.exec_command(script)
print(stdout.read().decode(errors='replace')[-4000:])
s.close()

