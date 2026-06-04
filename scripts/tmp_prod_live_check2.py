import paramiko, json
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('173.249.55.84', username='root', password='Temp1234..', timeout=30)

def run(cmd, t=120):
    print('\n$ ' + cmd)
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode('utf-8', 'replace')
    print(out[-20000:] if len(out)>20000 else out)

run('docker ps --format "{{.Names}} {{.Ports}}" | head -30')
run('docker port stokr-api 2>/dev/null; docker inspect stokr-api --format "{{json .NetworkSettings.Ports}}" 2>/dev/null | head -c 500')
run('curl -sS -m 5 -o /dev/null -w "%{http_code}" http://127.0.0.1:8000/health; echo')
run('curl -sS -m 5 -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/health; echo')
run('curl -sS -m 5 -o /dev/null -w "%{http_code}" http://127.0.0.1:80/api/health; echo')
run('docker exec stokr-redis redis-cli GET stokr:kill_switch')
run('docker exec stokr-redis redis-cli GET stokr:live_trading_armed')
run('docker logs stokr-api 2>&1 | grep -iE "SAFE_STARTUP|safe.startup|startup.not.ready" | tail -25')
run('docker logs stokr-api 2>&1 | grep -iE "StrategyExecutionMode|ADV_CASH.*LIVE|execution.mode" | tail -25')
c.close()
