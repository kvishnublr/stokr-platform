#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run(cmd, timeout=300):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    return (o.read() + e.read()).decode(errors="replace").strip()


if __name__ == "__main__":
    print("=== CONTAINERS ===")
    print(run("docker ps --format '{{.Names}} | {{.Ports}} | {{.Status}}' | grep api"))

    print("\n=== CYCLE_DONE WITH SIGNALS ===")
    print(run("docker logs stokr-api-new 2>&1 | grep 'catalog.scan.cycle_done' | grep -v 'signals=0' | head -40"))

    print("\n=== CYCLE_DONE 11:00-12:00 UTC (approx 16:30-17:30 server?) ===")
    print(run("docker logs stokr-api-new 2>&1 | grep 'catalog.scan.cycle_done' | grep '11:' | head -20"))

    print("\n=== SIGNAL PERSIST LOGS ===")
    print(run("docker logs stokr-api-new 2>&1 | grep -i 'signal.persist' | head -30"))

    print("\n=== FEED UNHEALTHY MORNING ===")
    print(run("docker logs stokr-api-new 2>&1 | grep -i 'feed_unhealthy\\|ingestion_paused\\|disconnect' | head -25"))

    print("\n=== API START TIME ===")
    print(run("docker inspect stokr-api-new --format 'Started: {{.State.StartedAt}} Health: {{.State.Health.Status}}'"))
    print(run("docker inspect stokr-api --format 'Started: {{.State.StartedAt}} Health: {{.State.Health.Status}}'"))

    print("\n=== HEALTH NOW ===")
    print(run("curl -sf http://127.0.0.1:8080/actuator/health"))
