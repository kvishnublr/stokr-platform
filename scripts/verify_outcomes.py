#!/usr/bin/env python3
import json
import os
import urllib.request

BASE = os.environ.get("STOKR_API_BASE", "http://127.0.0.1:8080")
USER = os.environ.get("STOKR_ADMIN_USER", "admin")
PASS = os.environ.get("STOKR_ADMIN_PASS", "password")


def main():
    login = json.loads(
        urllib.request.urlopen(
            urllib.request.Request(
                BASE + "/api/auth/login",
                data=json.dumps({"principal": USER, "password": PASS}).encode(),
                headers={"Content-Type": "application/json"},
                method="POST",
            ),
            timeout=60,
        ).read()
    )
    token = login["data"]["accessToken"]
    h = {"Authorization": f"Bearer {token}"}

    track = json.loads(
        urllib.request.urlopen(
            urllib.request.Request(BASE + "/api/admin/signals/track-outcomes", headers=h, method="POST"),
            timeout=300,
        ).read()
    )
    print("track-outcomes:", track.get("data"))

    stats = json.loads(
        urllib.request.urlopen(urllib.request.Request(BASE + "/api/admin/signals/stats", headers=h), timeout=60).read()
    )["data"]
    print("stats:", json.dumps(stats, indent=2))

    sigs = json.loads(
        urllib.request.urlopen(
            urllib.request.Request(BASE + "/api/admin/signals?page=0&size=5&includeTestTrades=false", headers=h),
            timeout=60,
        ).read()
    )["data"]
    print(f"total={sigs.get('totalElements')}")
    for r in sigs.get("content") or []:
        print(
            r.get("outcomeStatus"),
            r.get("realizedPnl"),
            r.get("unrealizedPnl"),
            r.get("strategyName"),
            r.get("symbol"),
        )


if __name__ == "__main__":
    main()
