import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

# Check what strategies the API returns (try public endpoint)
for endpoint in [
    "http://localhost:8081/api/strategies",
    "http://localhost:8081/api/marketplace/strategies",
    "http://localhost:8081/api/marketplace",
    "http://localhost:8081/api/deployments"
]:
    o = s.exec_command(f"curl -s -o /dev/null -w '%{{http_code}}' {endpoint}", get_pty=True)
    time.sleep(1)
    print(f"{endpoint}: HTTP {o[1].read().decode().strip()}")

# Check frontend API proxy
print("\n--- Frontend API to backend ---")
o2 = s.exec_command("docker exec stokr-lite-frontend wget -qO- http://backend:8081/api/strategies 2>&1 | head -100", get_pty=True)
time.sleep(3)
print(o2[1].read(4096).decode('utf-8', errors='replace')[:2000])

s.close()
