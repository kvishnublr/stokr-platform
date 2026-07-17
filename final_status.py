import paramiko, time, sys

s = paramiko.SSHClient()
s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
s.connect('173.249.55.84', username='root', password=sys.argv[1], timeout=20)

# Restart
print("Restarting backend...")
s.exec_command('cd /root/stokr-platform/stokr-lite && docker compose restart backend', get_pty=True)
time.sleep(15)

# Upload and run check script
check = 'import psycopg2\nc=psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")\ncur=c.cursor()\ncur.execute("SELECT version,description FROM flyway_schema_history ORDER BY version DESC LIMIT 3")\nfor r in cur.fetchall(): print("V"+str(r[0])+" - "+r[1])\ncur.execute("SELECT name,strategy_type,enabled FROM strategies ORDER BY id DESC LIMIT 5")\nprint("---")\nfor r in cur.fetchall(): print(r[0]+" ("+r[1]+") enabled="+str(r[2]))\nc.close()'

sftp = s.open_sftp()
with sftp.open('/tmp/check.py', 'w') as f:
    f.write(check)
sftp.close()

stdin, stdout, stderr = s.exec_command('su - postgres -c "python3 /tmp/check.py"', get_pty=True)
time.sleep(3)
print(stdout.read().decode())
print("Errors:", stderr.read().decode().strip())

# Health
stdin2, stdout2, stderr2 = s.exec_command('curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/actuator/health', get_pty=True)
time.sleep(2)
print("\nAPI:", stdout2.read().decode().strip())

s.close()
