import paramiko;s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='19119e3a6793dde1',timeout=30)
i,o,e=s.exec_command('docker ps --format "{{.Names}} {{.Status}}" | grep stokr')
print(o.read().decode())
i,o,e=s.exec_command('docker logs stokr-lite-backend --tail 5 2>&1')
out = o.read().decode(errors='replace')
for line in out.split('\n'):
    if 'Started' in line or 'ERROR' in line or 'Tomcat' in line:
        print(line[:200])
i,o,e=s.exec_command('curl -s http://localhost:8080/api/backtest/portfolio/model | head -c 200')
print("Model:", o.read().decode(errors='replace')[:200])
s.close()
