#!/usr/bin/env python3
"""Fetch raw Kite positions for Vishnu via server env + DB token."""
import json, paramiko, re
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect("173.249.55.84", username="root", password="Temp1234..", timeout=30)

def run(cmd, t=120):
    _, o, e = c.exec_command(cmd, timeout=t)
    return (o.read()+e.read()).decode()

UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

# get api key from env
env = run("docker exec stokr-api printenv | grep -iE 'KITE|ZERODHA' | grep -i KEY")
print("ENV keys:", env[:500])

row = run(f"""docker exec -i stokr-postgres psql -U postgres -d stokr_platform -t -A -F'|' -c "
SELECT vendor_code, access_token_enc FROM broker_accounts WHERE user_id='{UID}' AND deleted=false LIMIT 1;" """)
print("DB row:", row[:200])
parts = row.strip().split("|")
if len(parts) >= 2:
    token = parts[1].strip()
    # api key from compose env
    api_key = run("docker exec stokr-api printenv STOKR_ZERODHA_API_KEY").strip() or run("docker exec stokr-api printenv ZERODHA_API_KEY").strip()
    if not api_key:
        api_key = run("grep -r KITE_API_KEY /opt/stokr/stokr-platform/.env* 2>/dev/null | head -1")
        m = re.search(r'=(\w+)', api_key)
        api_key = m.group(1) if m else ""
    print("api_key:", api_key[:8] + "..." if api_key else "MISSING")
    if api_key and token:
        auth = f"{api_key}:{token}"
        raw = run(f"""curl -s -H 'Authorization: token {auth}' -H 'X-Kite-Version: 3' 'https://api.kite.trade/portfolio/positions'""")
        print("\n=== RAW KITE POSITIONS (truncated) ===")
        try:
            d = json.loads(raw)
            net = d.get("data", {}).get("net", [])
            day = d.get("data", {}).get("day", [])
            nonzero = [p for p in net + day if p.get("quantity", 0) != 0 or p.get("net_quantity", 0) != 0]
            print(f"status={d.get('status')} net_rows={len(net)} day_rows={len(day)} nonzero={len(nonzero)}")
            for p in nonzero[:25]:
                print(f"  {p.get('exchange')}:{p.get('tradingsymbol')} qty={p.get('quantity')} net_qty={p.get('net_quantity')} product={p.get('product')}")
        except Exception as ex:
            print(raw[:1500], ex)

c.close()
