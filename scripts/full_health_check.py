#!/usr/bin/env python3
"""Comprehensive system health check for live trading readiness."""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("STOKR_API_BASE", "http://localhost:8080")
ADMIN_USER = os.environ.get("STOKR_OPS_ADMIN_USER", "admin@stokr.local")
ADMIN_PASS = os.environ.get("STOKR_OPS_ADMIN_PASS", "admin123")
PASS, FAIL = 0, 0


def check(desc, ok, detail=""):
    global PASS, FAIL
    if ok:
        print(f"  \033[92mPASS\033[0m {desc} {detail}")
        PASS += 1
    else:
        print(f"  \033[91mFAIL\033[0m {desc} {detail}")
        FAIL += 1


def api(path):
    try:
        req = urllib.request.Request(BASE + path)
        resp = json.load(urllib.request.urlopen(req, timeout=10))
        return resp
    except Exception as e:
        return {"error": str(e)}


data = json.dumps({"principal": ADMIN_USER, "password": ADMIN_PASS}).encode()
req = urllib.request.Request(
    BASE + "/api/auth/login",
    data=data,
    headers={"Content-Type": "application/json"},
)
try:
    resp = json.load(urllib.request.urlopen(req, timeout=10))
    token = resp["data"]["accessToken"]
except Exception as e:
    print(f"  \033[91mFAIL\033[0m admin login {e}")
    sys.exit(1)

headers = {"Authorization": "Bearer " + token}


def authed(path, method="GET", body=None):
    try:
        req = urllib.request.Request(BASE + path, headers=headers, method=method)
        if body is not None:
            req.data = json.dumps(body).encode()
            req.add_header("Content-Type", "application/json")
        resp = json.load(urllib.request.urlopen(req, timeout=30))
        return resp
    except urllib.error.HTTPError as e:
        return {"error": e.code, "body": e.read().decode()[:300]}
    except Exception as e:
        return {"error": str(e)}


print("=== Docker Containers ===")
try:
    out = subprocess.check_output(
        ["docker", "ps", "--format", "{{.Names}}\t{{.Status}}"],
        timeout=10,
    ).decode()
    for line in out.strip().split("\n"):
        parts = line.split("\t")
        if len(parts) == 2:
            ok = "healthy" in parts[1] or "Up" in parts[1]
            check(parts[0], ok, parts[1])
except Exception as e:
    check("docker ps", False, str(e))

print("\n=== API Health ===")
h = api("/actuator/health")
check("actuator health", "error" not in h and h.get("status") == "UP", str(h.get("status", h))[:100])

print("\n=== Automation Health Report ===")
report = authed("/api/admin/operations/automation/health-report", method="POST")
if isinstance(report, dict) and "error" not in report:
    payload = report.get("data", report)
    healthy = payload.get("healthy", False)
    oauth = payload.get("oauthRequired", False)
    blockers = payload.get("readinessBlockers", [])
    check("automation health-report", healthy, f"blockers={blockers[:3]}")
    if oauth:
        check("oauth not required", False, "human Zerodha OAuth needed when refresh token missing")
    else:
        check("oauth not required", True)
else:
    check("automation health-report", False, str(report)[:200])
    h = api("/api/admin/health")
    check("health endpoint fallback", "error" not in h, str(h.get("status", h))[:100])

print("\n=== Database ===")
r = authed("/api/admin/readiness")
if isinstance(r, dict):
    check("readiness", "error" not in r, str(r)[:200])
else:
    check("readiness", False, "no response")

print("\n=== OMS Status ===")
oms = authed("/api/admin/oms/status")
if isinstance(oms, dict):
    oms_str = json.dumps(oms, default=str)
    check("oms/status", "error" not in oms, oms_str[:300])
else:
    check("oms/status", False)

st = authed("/api/admin/oms/stats")
if isinstance(st, dict):
    st_str = json.dumps(st, default=str)
    check("oms/stats", "error" not in st, st_str[:200])
else:
    check("oms/stats", False)

print("\n=== Risk Dashboard ===")
risk = authed("/api/admin/risk-dashboard")
if isinstance(risk, dict):
    risk_str = json.dumps(risk, default=str)
    check("risk-dashboard", "error" not in risk, risk_str[:300])
else:
    check("risk-dashboard", False)

print("\n=== Operations Snapshot ===")
ops = authed("/api/admin/operations/snapshot")
if isinstance(ops, dict):
    ops_str = json.dumps(ops, default=str)
    check("operations/snapshot", "error" not in ops, ops_str[:300])
else:
    check("operations/snapshot", False)

print("\n=== Broker Infrastructure ===")
bi = authed("/api/admin/broker-infrastructure")
if isinstance(bi, dict) and "data" in bi:
    z = bi["data"].get("vendors", {}).get("ZERODHA", {})
    check("Zerodha configured", z.get("configured", False))
    check("Zerodha connection_state", z.get("connectionState", "?"), z.get("connectionState", "?"))
    check("Zerodha has_refresh_token", z.get("hasRefreshToken", False))
    check("Zerodha websocket", z.get("websocketState", "?"))
    check("operational_live_path", z.get("operationalLivePath", False), z.get("operationalLivePathDetail", ""))
else:
    check("broker-infrastructure", False, str(bi)[:200])

print("\n=== Signal Stats ===")
ss = authed("/api/admin/signals/stats")
if isinstance(ss, dict):
    ss_str = json.dumps(ss, default=str)
    check("signals/stats", "error" not in ss, ss_str[:200])
else:
    check("signals/stats", False)

print("\n=== Users ===")
us = authed("/api/admin/users")
if isinstance(us, dict):
    users = us.get("data", us.get("users", []))
    if isinstance(users, list):
        check("users endpoint", True, f"{len(users)} users")
    else:
        check("users endpoint", True, str(us)[:100])
else:
    check("users endpoint", False)

print("\n=== Audit ===")
au = authed("/api/admin/audit")
check("audit endpoint", "error" not in au if isinstance(au, dict) else False)

print(f"\n\033[1m{'='*50}\033[0m")
print(f"  \033[92mPASS\033[0m: {PASS}  \033[91mFAIL\033[0m: {FAIL}  TOTAL: {PASS+FAIL}")
if FAIL == 0:
    print(f"  \033[92m\033[1mALL SYSTEMS GO\033[0m")
else:
    print(f"  \033[91m\033[1m{FAIL} BLOCKER(S) FOUND\033[0m")
print(f"\033[1m{'='*50}\033[0m")
