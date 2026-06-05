#!/usr/bin/env python3
import paramiko
c=paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('173.249.55.84', username='root', password='Temp1234..', timeout=30)

paths = [
    '/api/admin/diagnostics/health',
    '/api/admin/diagnostics/fake-endpoint-xyz',
    '/api/admin/operations/diagnostics',
    '/api/admin/diagnostics/protection',
    '/admin/dashboard',
]
for p in paths:
    _, o, e = c.exec_command(f'curl -s -o /dev/null -w "{p} %{{http_code}}" http://127.0.0.1:8080{p}')
    print(o.read().decode())

_, o, e = c.exec_command('docker exec stokr-api jar tf /app/app.jar | grep -E "AdminDiagnostics|AdminDashboard|AdminHealth|io/stokr" | head -30')
out = o.read().decode()
print('JAR matches:', out if out.strip() else 'NONE')

_, o, e = c.exec_command('cd /opt/stokr/stokr-platform && git log --oneline d682140f -1 2>&1; git merge-base --is-ancestor d682140f HEAD; echo merge_exit=$?')
print('Prod has d682:', o.read().decode())

c.close()
