#!/usr/bin/env python3
import json, urllib.request, urllib.error
BASE="http://localhost:8080"
TRADER="6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"
PASSWORDS=["password","admin","Temp1234"]

def post(path, body, token=None):
    h={"Content-Type":"application/json"}
    if token: h["Authorization"]="Bearer "+token
    req=urllib.request.Request(BASE+path,data=json.dumps(body).encode(),headers=h,method="POST")
    with urllib.request.urlopen(req,timeout=120) as r:
        return json.load(r)

token=None
for pwd in PASSWORDS:
    try:
        r=post("/api/auth/login",{"principal":"admin","password":pwd})
        if r.get("success") and r.get("data",{}).get("accessToken"):
            token=r["data"]["accessToken"]
            print("LOGIN_OK pwd="+pwd)
            break
    except urllib.error.HTTPError as e:
        print("LOGIN_FAIL pwd="+pwd+" "+str(e.code)+" "+e.read().decode()[:200])
if not token:
    print("LOGIN_FAILED"); raise SystemExit(1)

payload={"traderUserId":TRADER,"strategyKey":"OPENING_RANGE_BREAKOUT","symbol":"INFY","side":"BUY","quantity":1,"orderType":"MARKET","executionMode":"PAPER","triggerType":"INSTANT","forceQuantityOne":True,"dryRunOnly":False,"skipActualBrokerExecution":False,"simulateRejection":False,"simulateTimeout":False,"simulateStaleWebsocket":False,"simulateMarginFailure":False,"simulateBrokerDisconnect":False,"autoSquareOffMinutes":5}
try:
    out=post("/api/admin/test-signal-lab/run",payload,token)
except urllib.error.HTTPError as e:
    print("RUN_HTTP",e.code,e.read().decode()[:600]); raise SystemExit(2)
d=out.get("data") or {}
print("finalStatus",d.get("finalStatus"))
print("orderId",d.get("orderId"))
print("testId",d.get("testId") or d.get("id"))
for c in d.get("checks") or []:
    if c.get("status")=="FAILED":
        print("FAILED",c.get("label"),c.get("message"))
