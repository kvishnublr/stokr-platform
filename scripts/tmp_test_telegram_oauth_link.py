#!/usr/bin/env python3
"""Fix prod Telegram bot token and send OAuth test link."""
import json
import urllib.error
import urllib.request

import paramiko

HOST = "173.249.55.84"
BASE = f"http://{HOST}:8080"
MAIN = "/opt/stokr/stokr-platform"
VALID_TOKEN = "8691052981:AAHgLda7jRjarQNpx2pYCAHFdJQ1xAZ-t-o"
CHAT_ID = "8035979136"


def esc_html(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def send_telegram_html(bot_token: str, chat_id: str, html: str) -> dict:
    payload = json.dumps(
        {
            "chat_id": chat_id,
            "text": html,
            "parse_mode": "HTML",
            "disable_web_page_preview": True,
        }
    ).encode()
    req = urllib.request.Request(
        f"https://api.telegram.org/bot{bot_token}/sendMessage",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    return json.load(urllib.request.urlopen(req, timeout=20))


def main() -> None:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username="root", password="Temp1234..", timeout=30)

    fix_cmd = (
        f"cd {MAIN} && "
        "grep -q '^STOKR_TELEGRAM_BOT_TOKEN=' .env && "
        f"sed -i 's|^STOKR_TELEGRAM_BOT_TOKEN=.*|STOKR_TELEGRAM_BOT_TOKEN={VALID_TOKEN}|' .env || "
        f"echo 'STOKR_TELEGRAM_BOT_TOKEN={VALID_TOKEN}' >> .env"
    )
    client.exec_command(fix_cmd)[1].read()
    client.exec_command(
        f"cd {MAIN} && docker compose --profile app up -d --no-deps --force-recreate api"
    )[1].read()
    client.close()
    print("prod token updated + api restarting")

    import time

    for attempt in range(24):
        try:
            urllib.request.urlopen(BASE + "/actuator/health", timeout=5)
            print(f"api up after ~{attempt * 5}s")
            break
        except Exception:
            time.sleep(5)
    else:
        raise SystemExit("API did not become healthy in time")

    login_body = json.dumps(
        {"principal": "admin@stokr.local", "password": "admin123"}
    ).encode()
    token = json.load(
        urllib.request.urlopen(
            urllib.request.Request(
                BASE + "/api/auth/login",
                data=login_body,
                headers={"Content-Type": "application/json"},
            ),
            timeout=15,
        )
    )["data"]["accessToken"]

    connect = json.load(
        urllib.request.urlopen(
            urllib.request.Request(
                BASE + "/api/admin/broker-infrastructure/ZERODHA/connect",
                data=b"",
                method="POST",
                headers={"Authorization": "Bearer " + token},
            ),
            timeout=15,
        )
    )["data"]
    authorize_url = connect["authorizeUrl"]
    print("authorizeUrl ok")

    html = (
        "🔐 <b>[TEST] Zerodha reconnect required</b>\n\n"
        "Tap below to log in on Kite from your phone. Tokens save automatically after login.\n\n"
        f'<a href="{esc_html(authorize_url)}">Connect Zerodha on Kite</a>\n\n'
        "Reason: manual_test"
    )

    try:
        resp = send_telegram_html(VALID_TOKEN, CHAT_ID, html)
        print("telegram sent ok=", resp.get("ok"), "message_id=", resp.get("result", {}).get("message_id"))
    except urllib.error.HTTPError as ex:
        print("telegram failed", ex.code, ex.read().decode()[:800])
        raise SystemExit(1)


if __name__ == "__main__":
    main()
