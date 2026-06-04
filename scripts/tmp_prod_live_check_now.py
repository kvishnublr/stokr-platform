import paramiko, json, re
host='173.249.55.84'
BASE='/opt/stokr/stokr-platform'
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(host, username='root', password='Temp1234..', timeout=30)

def run(cmd, t=120):
    print('\n$ ' + cmd[:200])
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode('utf-8', 'replace')
    print(out[-15000:] if len(out) > 15000 else out)
    return out

run(f'cd {BASE} && git rev-parse --short HEAD && git branch --show-current')
run('curl -sS -m 20 http://127.0.0.1:8000/api/admin/operations/diagnostics 2>/dev/null')
run('curl -sS -m 10 http://127.0.0.1:8000/health 2>/dev/null')
run('docker exec stokr-redis redis-cli GET stokr:strategy:ADV_CASH:enabled')
run('docker exec stokr-redis redis-cli KEYS "stokr:*kill*" 2>/dev/null; docker exec stokr-redis redis-cli KEYS "*live*arm*" 2>/dev/null; docker exec stokr-redis redis-cli KEYS "stokr:*ADV_CASH*" 2>/dev/null')
c.close()
