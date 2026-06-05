#!/usr/bin/env python3
import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd):
    _, o, e = c.exec_command(cmd, timeout=90)
    return (o.read() + e.read()).decode(errors="replace")

print(run('docker ps -a --filter name=stokr-api --format "{{.Names}} {{.Status}}"'))
print("\n=== LOGS ===\n", run("docker logs stokr-api 2>&1 | tail -100"))
print("\n=== HEALTH ===", run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health"))
print("\n=== FLYWAY ===\n", run("""docker exec stokr-postgres psql -U postgres -d stokr_platform -c "SELECT version,description,success FROM flyway_schema_history WHERE version IN ('98','99');" """))
print("\n=== JAR ===\n", run("docker exec stokr-api jar tf /app/stokr-bootstrap.jar 2>/dev/null | grep -E 'AdminDiagnostics|AdminDashboard|io/stokr/bootstrap' | head -20"))
c.close()
