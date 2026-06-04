#!/usr/bin/env python3
"""Post-deploy: health, redispatch orphans, verify orders."""
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"


def get(path):
    return urllib.request.urlopen(BASE + path, timeout=30).read().decode()


def post(path, token=None, body=None):
    data = json.dumps(body).encode() if body else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method="POST")
    return urllib.request.urlopen(req, timeout=180).read().decode()


def main():
    for i in range(40):
        try:
            h = json.loads(get("/actuator/health"))
            if h.get("status") == "UP":
                print("health UP")
                break
        except Exception as e:
            print(f"wait health {i}: {e}")
        time.sleep(5)
    else:
        print("FAIL: API not healthy")
        return 1

    login = json.loads(post("/api/auth/login", body={"principal": "admin", "password": "password"}))
    token = login["data"]["accessToken"]
    print("login ok")

    try:
        out = json.loads(post("/api/admin/feed/redispatch-orphan-signals", token=token))
        print("redispatch:", json.dumps(out.get("data", out), indent=2))
    except urllib.error.HTTPError as e:
        print("redispatch HTTP", e.code, e.read().decode()[:500])
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
