import paramiko
import time

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
time.sleep(5)
for cmd in [
    "curl -sf http://127.0.0.1:8080/actuator/health",
    'curl -s "https://stokr.in/api/broker/zerodha/callback?status=error&state=00000000-0000-0000-0000-000000000000" | head -5',
]:
    _, o, e = c.exec_command(cmd)
    print("===", cmd, "===")
    print((o.read() + e.read()).decode())
c.close()
