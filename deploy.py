#!/usr/bin/env python3
"""Deploy stokr-lite to production server."""
import paramiko
import os
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

HOST = "173.249.55.84"
USER = "root"
PASS = os.environ.get("SSH_PASSWORD", "")
if not PASS:
    print("ERROR: Set SSH_PASSWORD env var")
    sys.exit(1)
JAR_LOCAL = r"C:\Users\itsvi\Desktop\work_new\stokr-platform\stokr-lite\backend\target\stokr-lite-1.0.0-SNAPSHOT.jar"
JAR_REMOTE = "/root/stokr-lite.jar"
REPO_PATH = "/root/stokr-platform"

def run_ssh(ssh, cmd, desc=""):
    print(f"  [{desc}] {cmd}")
    stdin, stdout, stderr = ssh.exec_command(cmd)
    out = stdout.read().decode(errors='replace').strip()
    err = stderr.read().decode(errors='replace').strip()
    if out:
        print(out)
    if err:
        print(f"  STDERR: {err}")
    return out, err

print("Connecting...")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username=USER, password=PASS, timeout=30)
print("Connected!")

# 1. Check server state
print("\n=== Server Info ===")
run_ssh(ssh, "uname -a", "OS")
run_ssh(ssh, "java -version 2>&1", "Java")
run_ssh(ssh, "docker --version 2>&1 || echo 'no docker'", "Docker")
run_ssh(ssh, "ls -la /root/stokr-lite.jar 2>/dev/null || echo 'no existing jar'", "Current JAR")

# 2. Pull latest code & build on server
print("\n=== Git Pull ===")
run_ssh(ssh, f"[ -d {REPO_PATH} ] && (cd {REPO_PATH} && git fetch && git checkout Release_v8 && git reset --hard && git pull origin Release_v8) || git clone https://github.com/kvishnublr/stokr-platform.git {REPO_PATH}", "Clone/Pull")

# 3. Build
print("\n=== Maven Build ===")
run_ssh(ssh, f"cd {REPO_PATH}/stokr-lite/backend && mvn clean package -DskipTests -q", "mvn package")

# 4. Check for docker-compose
out, _ = run_ssh(ssh, f"ls {REPO_PATH}/stokr-lite/docker-compose.yml 2>/dev/null && echo 'FOUND' || echo 'NOT FOUND'", "docker-compose check")

if "FOUND" in out:
    print("\n=== Docker Deploy ===")
    run_ssh(ssh, f"cd {REPO_PATH}/stokr-lite && docker-compose down", "Stop")
    run_ssh(ssh, f"cd {REPO_PATH}/stokr-lite && docker-compose up -d --build", "Start")
else:
    # Direct JAR deploy
    print("\n=== Direct JAR Deploy ===")
    # Kill existing
    run_ssh(ssh, "pkill -f 'stokr-lite' 2>/dev/null; sleep 2; echo 'killed'", "Kill old process")
    # Copy JAR
    run_ssh(ssh, f"cp {REPO_PATH}/stokr-lite/backend/target/stokr-lite-1.0.0-SNAPSHOT.jar {JAR_REMOTE}", "Copy JAR")
    # Start
    run_ssh(ssh, f"nohup java -jar {JAR_REMOTE} --spring.profiles.active=prod > /var/log/stokr-lite.log 2>&1 &", "Start")
    import time
    time.sleep(5)
    run_ssh(ssh, "ps aux | grep 'stokr-lite' | grep -v grep", "Check process")

# 4b. Also deploy to systemd production path if it exists
run_ssh(ssh, f"cp {REPO_PATH}/stokr-lite/backend/target/stokr-lite-1.0.0-SNAPSHOT.jar /opt/stokr/stokr-lite.jar", "Copy to prod JAR")
run_ssh(ssh, "systemctl restart stokr-lite.service && sleep 3 && systemctl is-active stokr-lite.service", "Restart prod systemd")

# 5. Verify health
print("\n=== Health Check ===")
import time
time.sleep(3)
run_ssh(ssh, "curl -s http://localhost:8081/actuator/health 2>/dev/null || echo 'not responding yet'", "Health")

ssh.close()
print("\nDeploy complete!")
