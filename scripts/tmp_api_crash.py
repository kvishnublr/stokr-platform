import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
"docker inspect stokr-api --format 'RestartCount={{.RestartCount}} Status={{.State.Status}} StartedAt={{.State.StartedAt}}'",
"docker logs stokr-api 2>&1 | grep -iE 'Exception|ERROR|OOM|killed' | tail -20",
"docker events --since 30m --filter container=stokr-api --filter event=restart 2>/dev/null | tail -10",
]
for cmd in cmds:
    _, o, e = c.exec_command(cmd, timeout=60)
    print((o.read()+e.read()).decode()[-3000:])
c.close()
