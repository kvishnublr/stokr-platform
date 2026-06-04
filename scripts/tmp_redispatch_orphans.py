#!/usr/bin/env python3
import json
import urllib.request

BASE = "http://127.0.0.1:8080"

def post(path, token=None, body=None):
    data = json.dumps(body).encode() if body else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method="POST")
    return urllib.request.urlopen(req, timeout=120).read().decode()

login = json.loads(post("/api/auth/login", body={"principal": "admin", "password": "password"}))
token = login["data"]["accessToken"]
print("login ok")
out = post("/api/admin/feed/redispatch-orphan-signals", token=token)
print(out)
