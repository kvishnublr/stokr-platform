import paramiko, time
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('173.249.55.84', username='root', password='Temp1234..', timeout=30)

def run(cmd, t=120):
    _, o, e = c.exec_command(cmd, timeout=t)
    return (o.read() + e.read()).decode('utf-8', 'replace')

out = run('docker logs stokr-api 2>&1 | grep -iE "Started StokrApplication|safe.startup|SAFE_STARTUP|Tomcat started" | tail -20')
print('startup markers:', out or '(none yet)')
out2 = run('docker logs stokr-api 2>&1 | tail -5')
print('tail:', out2)
c.close()
