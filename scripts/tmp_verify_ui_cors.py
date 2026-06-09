#!/usr/bin/env python3
import os, paramiko, json, urllib.request
HOST = "173.249.55.84"
PW = os.environ.get("STOKR_PROD_SSH_PASS", "Temp1234..")
ssh = paramiko.SSHClient(); ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy()); ssh.connect(HOST, username="root", password=PW, timeout=30)
_, o, _ = ssh.exec_command("docker exec stokr-api printenv STOKR_CORS_ALLOWED_ORIGINS", timeout=20)
print("CORS in container:", o.read().decode().strip())
ssh.close()

# CORS preflight
req = urllib.request.Request(
    "http://173.249.55.84:8080/api/auth/login",
    method="OPTIONS",
    headers={
        "Origin": "http://173.249.55.84:8082",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type,authorization",
    },
)
try:
    with urllib.request.urlopen(req, timeout=15) as r:
        print("OPTIONS status", r.status)
        for h in ["Access-Control-Allow-Origin", "Access-Control-Allow-Methods"]:
            print(h + ":", r.headers.get(h))
except Exception as e:
    print("OPTIONS failed", e)

# admin login with origin
body = json.dumps({"principal": "admin@stokr.local", "password": "admin123"}).encode()
req2 = urllib.request.Request(
    "http://173.249.55.84:8080/api/auth/login",
    data=body,
    headers={"Content-Type": "application/json", "Origin": "http://173.249.55.84:8083"},
    method="POST",
)
with urllib.request.urlopen(req2, timeout=15) as r:
    data = json.loads(r.read().decode())
    print("login ACAO:", r.headers.get("Access-Control-Allow-Origin"))
    tok = data["data"]["accessToken"]
    print("admin token ok")

req3 = urllib.request.Request(
    "http://173.249.55.84:8080/api/admin/health",
    headers={"Authorization": "Bearer " + tok, "Origin": "http://173.249.55.84:8083"},
)
with urllib.request.urlopen(req3, timeout=15) as r:
    print("admin health:", json.loads(r.read().decode())["data"].keys())
