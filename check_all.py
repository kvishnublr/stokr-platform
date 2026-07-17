import paramiko, time

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password='***', timeout=20)

# Upload the check script
sftp = s.open_sftp()
sftp.put(r"C:\Users\itsvi\Desktop\work_new\stokr-platform\server_check.py", "/root/server_check.py")
sftp.close()

# Run it
i, o, e = s.exec_command("python3 /root/server_check.py", get_pty=True)
time.sleep(5)
print(o.read(8192).decode())

# Also check built frontend JS for INSIDER
i2, o2, e2 = s.exec_command(
    "docker exec stokr-lite-frontend sh -c 'grep INSIDER /usr/share/nginx/html/assets/*.js | head -c 300' 2>&1",
    get_pty=True)
time.sleep(2)
print("\n--- Frontend INSIDER check ---")
print(o2.read(2048).decode())

# Check the source file inside container
i3, o3, e3 = s.exec_command(
    "docker exec stokr-lite-frontend sh -c 'cat /usr/share/nginx/html/assets/Strategies*.js | grep -o PROFITABLE_STRATEGIES.*INSIDER_MOMENTUM || echo NO_INSIDER_IN_BUILD' 2>&1",
    get_pty=True)
time.sleep(2)
print("\n--- Built JS contains Insider? ---")
print(o3.read(2048).decode())

s.close()
