import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

# Check running container
i, o, e = s.exec_command(
    "docker exec stokr-lite-frontend sh -c 'grep -l INSIDER_MOMENTUM /usr/share/nginx/html/assets/*.js' 2>&1",
    get_pty=True)
time.sleep(2)
print("Files with INSIDER:", o.read().decode().strip())

# Check the Strategies JS file content
i2, o2, e2 = s.exec_command(
    "docker exec stokr-lite-frontend sh -c 'grep -c INSIDER_MOMENTUM /usr/share/nginx/html/assets/Strategies*.js' 2>&1",
    get_pty=True)
time.sleep(2)
print("INSIDER count:", o2.read().decode().strip())

# Quick curl
time.sleep(3)
i3, o3, e3 = s.exec_command(
    "curl -s http://localhost:8082 2>&1 | grep -c strategy 2>&1",
    get_pty=True)
time.sleep(2)
print("\nFrontend serves HTML:", "OK" if "nginx" in o3.read().decode().lower() or "html" in o3.read().decode().lower() else o3.read().decode()[:100])

s.close()
