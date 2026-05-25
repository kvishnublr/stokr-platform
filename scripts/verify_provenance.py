#!/usr/bin/env python3
"""Verify signal provenance isolation and production analytics counts."""
import json
import os
import urllib.request

BASE = os.environ.get("STOKR_API_BASE", "http://173.249.55.84:8080")
USER = os.environ.get("STOKR_ADMIN_USER", "admin")
PASS = os.environ.get("STOKR_ADMIN_PASS", "password")


def get(path, token=None, method="GET", body=None):
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode())


def main():
    login = get("/api/auth/login", method="POST", body={"principal": USER, "password": PASS})
    token = login["data"]["accessToken"]

    prod_stats = get("/api/admin/signals/stats", token=token)
    print("production stats (LIVE+PAPER only):", json.dumps(prod_stats.get("data"), indent=2))

    prod = get("/api/admin/signals?page=0&size=3&includeTestTrades=false", token=token)
    print("production list total:", prod["data"].get("totalElements"))

    all_rows = get("/api/admin/signals?page=0&size=3&includeReplayAndLab=true", token=token)
    print("including replay/lab total:", all_rows["data"].get("totalElements"))

    health = get("/actuator/health")
    print("health:", health.get("status"))


if __name__ == "__main__":
    main()
