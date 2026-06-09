#!/usr/bin/env python3
import os, paramiko, time
PW = os.environ.get("STOKR_PROD_SSH_PASS", "Temp1234..")
ssh = paramiko.SSHClient(); ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy()); ssh.connect("173.249.55.84", username="root", password=PW, timeout=30)

def run(cmd):
    _, o, e = ssh.exec_command(cmd, timeout=120)
    return (o.read()+e.read()).decode()

for i in range(12):
    h = run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health")
    print("health attempt", i+1, h.strip())
    if h.strip() == "200":
        break
    time.sleep(5)

print(run("""curl -s -D - -o /tmp/login.json -X OPTIONS 'http://127.0.0.1:8080/api/auth/login' \
  -H 'Origin: http://173.249.55.84:8082' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: content-type' | head -20"""))

print(run("""curl -s -D - -o /tmp/login.json -X POST 'http://127.0.0.1:8080/api/auth/login' \
  -H 'Origin: http://173.249.55.84:8083' \
  -H 'Content-Type: application/json' \
  -d '{"principal":"admin@stokr.local","password":"admin123"}' | head -15"""))
print(run("head -c 200 /tmp/login.json"))
ssh.close()
