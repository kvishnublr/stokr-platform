import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

cmds = [
    'curl -sI "https://stokr.in/brokers/zerodha-complete?platform_feed=ok" | head -10',
    "docker logs stokr-api --since 60m 2>&1 | grep -i zerodha | tail -20",
    "docker logs stokr-api --since 60m 2>&1 | grep -i callback | tail -10",
]
for cmd in cmds:
    print("===", cmd, "===")
    _, o, e = c.exec_command(cmd)
    print((o.read() + e.read()).decode())

c.close()
