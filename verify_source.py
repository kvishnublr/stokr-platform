import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

# 1. Verify source file ON SERVER has INSIDER_MOMENTUM
i, o, e = s.exec_command(
    "grep INSIDER_MOMENTUM /root/stokr-platform/stokr-lite/frontend/src/pages/Strategies.jsx 2>&1 || echo MISSING",
    get_pty=True)
time.sleep(1)
print("Server source file:", o.read().decode().strip())

# 2. Verify the dist/built version in Docker image
i2, o2, e2 = s.exec_command(
    "docker run --rm stokr-lite-frontend grep INSIDER_MOMENTUM /usr/share/nginx/html/assets/Strategies*.js 2>&1 || echo NOT_IN_IMAGE",
    get_pty=True)
time.sleep(2)
print("Docker image:", o2.read().decode().strip())

s.close()
