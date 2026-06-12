import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
    "docker logs stokr-api --since 30m 2>&1 | grep -E 'terminal/workstation|exposure_failed|InvalidTypeId|ClassCastException' | tail -25",
    "docker logs stokr-api --since 30m 2>&1 | grep -E 'Unhandled error|500 Internal' | tail -10",
    "cd /opt/stokr/stokr-platform && git log -3 --oneline",
]
for cmd in cmds:
    _, o, e = c.exec_command(cmd)
    print(">>>", cmd)
    print((o.read() + e.read()).decode())
c.close()
