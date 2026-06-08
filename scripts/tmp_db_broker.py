import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)
cmds = [
    'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "\\dt *broker*"',
    'docker exec stokr-postgres psql -U postgres -d stokr_platform -t -c "SELECT email FROM auth_users WHERE id=\'6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4\';"',
    "docker logs stokr-api --since 20m 2>&1 | grep ORPHAN_BROKER | tail -12",
]
for cmd in cmds:
    _, o, e = c.exec_command(cmd)
    print(">>>", cmd)
    print((o.read() + e.read()).decode())
c.close()
