#!/usr/bin/env python3
"""Flatten Vishnu open Zerodha positions via trader terminal API."""
import json
import urllib.request

import paramiko

HOST = "173.249.55.84"
UID = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"


def curl_on_server(c, args: str) -> str:
  _, o, e = c.exec_command(args, timeout=120)
  return (o.read() + e.read()).decode("utf-8", "replace")


def main():
  c = paramiko.SSHClient()
  c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
  c.connect(HOST, username="root", password="Temp1234..", timeout=30)

  print("=== execution mode preference ===")
  sql = f"SELECT execution_mode, live_enabled FROM user_execution_preferences WHERE user_id='{UID}';"
  print(curl_on_server(c, f'docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "{sql}"'))

  print("=== broker account ===")
  sql2 = f"SELECT connection_state, token_expires_at > NOW() AS token_valid FROM broker_accounts WHERE user_id='{UID}' AND deleted=false;"
  print(curl_on_server(c, f'docker exec -i stokr-postgres psql -U postgres -d stokr_platform -c "{sql2}"'))

  # login as vishnu - try common passwords
  for principal, pwd in [
      ("vishnualgo@gmail.com", "admin123"),
      ("vishnualgo@gmail.com", "Temp@12345678"),
  ]:
    login_raw = curl_on_server(
        c,
        f"""curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{{"principal":"{principal}","password":"{pwd}"}}'""",
    )
    try:
      data = json.loads(login_raw).get("data") or {}
      token = data.get("accessToken")
      if not token:
        print("login failed", principal, login_raw[:200])
        continue
      print("logged in as", principal)
      preview = curl_on_server(
          c,
          f"""curl -s 'http://127.0.0.1:8080/api/trader/execution/terminal/control/preview?action=FLATTEN_POSITIONS' -H 'Authorization: Bearer {token}'""",
      )
      print("preview:", preview[:1200])
      prev = json.loads(preview).get("data") or {}
      confirm = prev.get("confirmationToken")
      if not confirm:
        print("no confirmation token")
        break
      exec_raw = curl_on_server(
          c,
          f"""curl -s -X POST http://127.0.0.1:8080/api/trader/execution/terminal/control/execute -H 'Authorization: Bearer {token}' -H 'Content-Type: application/json' -d '{{"action":"FLATTEN_POSITIONS","confirmationToken":"{confirm}"}}'""",
      )
      print("execute:", exec_raw[:2500])
      break
    except json.JSONDecodeError:
      print("bad json", login_raw[:300])

  c.close()


if __name__ == "__main__":
  main()
