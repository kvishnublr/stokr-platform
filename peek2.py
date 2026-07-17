import paramiko;s=paramiko.SSHClient();s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84',username='root',password='***',timeout=10)
i,o,e=s.exec_command('docker logs stokr-lite-backend --tail 30 2>&1')
log = o.read().decode(errors='replace')
for line in log.split('\n'):
    if any(k in line for k in ['Started','Backtest','ERROR','Exception','Caused','WARN']):
        print(line[:250])
i,o,e=s.exec_command('curl -s -m5 http://localhost:8080/actuator/health 2>/dev/null')
print('Health:', o.read().decode(errors='replace'))
i,o,e=s.exec_command('docker ps --format "{{.Names}} {{.Status}}" | grep stokr')
print('Containers:', o.read().decode(errors='replace'))
s.close()
