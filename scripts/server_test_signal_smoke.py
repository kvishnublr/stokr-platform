#!/usr/bin/env python3
"""Smoke test for Admin Test Signal Lab on Contabo."""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
TRADER = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
PASSWORDS = ["password", "admin", "Temp1234"]


def post(path: str, body: dict, token: str | None = None) -> dict:
    data = json.dumps(body).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{BASE}{path}", data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.load(resp)


def get(path: str, token: str) -> dict:
    req = urllib.request.Request(
        f"{BASE}{path}",
        headers={"Authorization": f"Bearer {token}"},
        method="GET",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def main() -> int:
    token = None
    user_id = TRADER
    for pwd in PASSWORDS:
        try:
            login = post("/api/auth/login", {"principal": "admin", "password": pwd})
            if login.get("success") and login.get("data", {}).get("accessToken"):
                token = login["data"]["accessToken"]
                user_id = login["data"].get("userId") or TRADER
                print(f"LOGIN_OK password={pwd} userId={user_id}")
                break
        except urllib.error.HTTPError as ex:
            body = ex.read().decode(errors="replace")
            print(f"LOGIN_FAIL password={pwd} status={ex.code} body={body[:300]}")
        except Exception as ex:
            print(f"LOGIN_FAIL password={pwd} err={ex}")

    if not token:
        print("LOGIN_FAILED")
        return 1

    payload = {
        "traderUserId": TRADER,
        "strategyKey": "OPENING_RANGE_BREAKOUT",
        "symbol": "NSE:ITC",
        "side": "BUY",
        "quantity": 1,
        "productType": "MIS",
        "orderType": "MARKET",
        "executionMode": "LIVE",
        "triggerType": "INSTANT",
        "forceQuantityOne": True,
        "dryRunOnly": False,
        "skipActualBrokerExecution": False,
        "simulateRejection": False,
        "simulateTimeout": False,
        "simulateStaleWebsocket": False,
        "simulateMarginFailure": False,
        "simulateBrokerDisconnect": False,
        "autoSquareOffMinutes": 0,
    }

    print("RUN_TEST_SIGNAL LIVE MIS ...")
    t0 = time.perf_counter()
    try:
        run_resp = post("/api/admin/test-signal-lab/run", payload, token)
    except urllib.error.HTTPError as ex:
        print(f"RUN_HTTP_ERROR status={ex.code} body={ex.read().decode(errors='replace')[:800]}")
        return 2

    elapsed_ms = int((time.perf_counter() - t0) * 1000)
    data = run_resp.get("data") or {}
    final = data.get("finalStatus")
    status = data.get("status")
    order_id = data.get("orderId")
    test_id = data.get("testId") or data.get("id")
    latency = data.get("totalLatencyMs")
    summary = data.get("summary") or {}
    print(
        f"RUN_RESULT finalStatus={final} status={status} orderId={order_id} "
        f"testId={test_id} apiRoundTripMs={elapsed_ms} totalLatencyMs={latency} "
        f"squareOff={summary.get('squareOffStatus')}"
    )

    failed = [c for c in (data.get("checks") or []) if c.get("status") == "FAILED"]
    for c in failed:
        print(f"FAILED_CHECK {c.get('label')}: {c.get('message')}")

    print("JSON_RESULT=" + json.dumps({
        "finalStatus": final,
        "orderId": order_id,
        "testId": test_id,
        "totalLatencyMs": latency,
        "apiRoundTripMs": elapsed_ms,
        "squareOffStatus": summary.get("squareOffStatus"),
        "failed": failed,
    }))
    return 0 if final == "SUCCESS" else 3


if __name__ == "__main__":
    sys.exit(main())
