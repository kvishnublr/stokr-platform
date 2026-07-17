import paramiko,time
s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
script = r"""
cd /root/stokr-platform && git pull origin Release_v8
docker rm -f stokr-lite-backend stokr-lite-frontend 2>/dev/null
cd /root/stokr-platform/stokr-lite && docker compose up -d --build 2>&1 | tail -5
sleep 35
echo "=== Status ==="
docker ps --format '{{.Names}} {{.Status}}' | grep stokr
echo "=== Logs ==="
docker logs stokr-lite-backend 2>&1 | grep -E 'Started|ERROR|Flyway|migrate|8080|WARN.*schema|Exception|JVM.*in' | tail -5
echo "=== Health ==="
curl -s http://localhost:8080/actuator/health
echo ""
echo "=== Portfolio ==="
curl -s http://localhost:8080/api/backtest/portfolio/model
echo ""
echo "=== QuickFlip ==="
curl -s http://localhost:8080/api/backtest/quickflip/model | head -c 300
"""
stdin,stdout,stderr = s.exec_command(script)
print(stdout.read().decode(errors='replace')[-4000:])
s.close()
