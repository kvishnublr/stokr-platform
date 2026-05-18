#!/usr/bin/env python3
"""
stokr-platform  ·  Zerodha 1-min candle seeder
===============================================
Fetches 1-min OHLCV candles from Zerodha Kite API → marketdata_candles table.

Symbols : NIFTY 200 equity  +  NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY futures
Depth   : last 60 calendar days per run  (Zerodha hard limit for 1-min data)
Token   : read automatically from platform_broker_feed_sessions (DB) — no manual copy

Modes
-----
  python seed.py --mode seed       one-time fill of last 60 days
  python seed.py --mode daemon     seed once, then refresh daily at 16:15 IST
  python seed.py --mode truncate   wipe all market-data tables (asks YES)

Config  (.env or environment variables)
-------
  ZERODHA_API_KEY        Kite Connect API key  (same as STOKR_ZERODHA_API_KEY)
  STOKR_CRYPTO_FIELD_KEY Platform field-cipher key  (same as the Spring app uses)
  DB_URL                 postgresql://stokr:stokr@localhost:5432/stokr_platform
  SEED_DAYS              calendar days to back-fill on first seed  (default 60)
  CHUNK_DAYS             calendar days per Zerodha API call        (default 40)

Token flow
----------
  1. Script queries  platform_broker_feed_sessions  WHERE vendor_code='ZERODHA'
     and connection_state='CONNECTED'  (the admin Zerodha session)
  2. Decrypts access_token_enc  using STOKR_CRYPTO_FIELD_KEY  (AES-256-GCM)
  3. Sets token on KiteConnect and proceeds

  As long as the admin reconnects Zerodha daily in the platform UI (which is
  required for live trading anyway), this script always picks up the fresh token
  automatically.

  The daemon re-fetches the token from DB before every daily refresh so a
  token renewed during the day is picked up without restart.
"""

import argparse
import base64
import datetime
import logging
import os
import sys
import time
import uuid

import psycopg2
import psycopg2.extras
import pytz
import requests
import schedule
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from dotenv import load_dotenv
from kiteconnect import KiteConnect

load_dotenv()

# ── logging ───────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("seed")

# ── config ────────────────────────────────────────────────────────────────────
API_KEY        = os.environ.get("ZERODHA_API_KEY", os.environ.get("STOKR_ZERODHA_API_KEY", ""))
FIELD_KEY_B64  = os.environ.get("STOKR_CRYPTO_FIELD_KEY", "")
DB_URL         = os.environ.get("DB_URL", "postgresql://stokr:stokr@localhost:5432/stokr_platform")
SEED_DAYS      = int(os.environ.get("SEED_DAYS", "60"))
CHUNK_DAYS     = int(os.environ.get("CHUNK_DAYS", "40"))

TIMEFRAME     = "1m"
IST           = pytz.timezone("Asia/Kolkata")
FUTURES_BASES = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"]

# ── AES-256-GCM token decryption ──────────────────────────────────────────────
# Matches AesGcmFieldCipher.java exactly:
#   stored  = Base64.getEncoder().encodeToString( iv[12] + ciphertext+tag )
#   key     = Base64.getDecoder().decode(STOKR_CRYPTO_FIELD_KEY)  → 32 bytes

def _decrypt_field(encrypted_b64: str, field_key_b64: str) -> str:
    key      = base64.b64decode(field_key_b64)          # 32-byte AES-256 key
    all_b    = base64.b64decode(encrypted_b64)           # iv(12) + ct+tag
    iv       = all_b[:12]
    ct_tag   = all_b[12:]
    return AESGCM(key).decrypt(iv, ct_tag, None).decode()


def load_zerodha_token(conn) -> str:
    """
    Read and decrypt the active Zerodha access token from the database.

    Tries platform_broker_feed_sessions first (admin-level market-data session).
    Falls back to broker_accounts (user-level trading session) if platform
    session is not found or not connected.
    """
    if not FIELD_KEY_B64:
        log.error("STOKR_CRYPTO_FIELD_KEY is not set — cannot decrypt stored token.")
        sys.exit(1)

    with conn.cursor() as cur:
        # ── platform-level session (preferred) ──────────────────────────────
        cur.execute("""
            SELECT access_token_enc
            FROM   platform_broker_feed_sessions
            WHERE  vendor_code        = 'ZERODHA'
              AND  connection_state   = 'CONNECTED'
              AND  deleted            = FALSE
              AND  access_token_enc   IS NOT NULL
            ORDER  BY updated_at DESC
            LIMIT  1
        """)
        row = cur.fetchone()
        if row:
            log.info("Token source: platform_broker_feed_sessions (admin session)")
            return _decrypt_field(row[0], FIELD_KEY_B64)

        # ── user-level broker account (fallback) ────────────────────────────
        cur.execute("""
            SELECT access_token_enc
            FROM   broker_accounts
            WHERE  vendor_code       = 'ZERODHA'
              AND  status            = 'CONNECTED'
              AND  deleted           = FALSE
              AND  access_token_enc  IS NOT NULL
            ORDER  BY updated_at DESC
            LIMIT  1
        """)
        row = cur.fetchone()
        if row:
            log.info("Token source: broker_accounts (trader session)")
            return _decrypt_field(row[0], FIELD_KEY_B64)

    log.error(
        "No active Zerodha session found in the database.\n"
        "  → Go to Admin UI → Broker Infrastructure → Connect Zerodha first."
    )
    sys.exit(1)


# ── NIFTY 200 fallback (used when NSE CSV download fails) ─────────────────────
NIFTY200_FALLBACK = [
    # NIFTY 50
    "ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK",
    "BAJAJ-AUTO", "BAJFINANCE", "BAJAJFINSV", "BHARTIARTL", "BPCL",
    "BRITANNIA", "CIPLA", "COALINDIA", "DIVISLAB", "DRREDDY",
    "EICHERMOT", "GRASIM", "HCLTECH", "HDFCBANK", "HDFCLIFE",
    "HEROMOTOCO", "HINDALCO", "HINDUNILVR", "ICICIBANK", "INDUSINDBK",
    "INFY", "ITC", "JSWSTEEL", "KOTAKBANK", "LT",
    "LTIM", "M&M", "MARUTI", "NESTLEIND", "NTPC",
    "ONGC", "POWERGRID", "RELIANCE", "SBILIFE", "SBIN",
    "SUNPHARMA", "TATACONSUM", "TATAMOTORS", "TATASTEEL", "TCS",
    "TECHM", "TITAN", "ULTRACEMCO", "UPL", "WIPRO",
    # NIFTY NEXT 50
    "ADANIGREEN", "AMBUJACEM", "APOLLOTYRE", "ATGL", "AUBANK",
    "BANKBARODA", "BERGEPAINT", "BOSCHLTD", "CANBK", "CHOLAFIN",
    "CONCOR", "DABUR", "DALBHARAT", "DLF", "DMART",
    "FEDERALBNK", "GODREJCP", "GODREJPROP", "HAVELLS", "HDFCAMC",
    "ICICIGI", "IDFCFIRSTB", "IGL", "INDIGO", "INDUSTOWER",
    "IRCTC", "JINDALSTEL", "LUPIN", "MARICO", "MOTHERSON",
    "MUTHOOTFIN", "NHPC", "NAUKRI", "NMDC", "OBEROIRLTY",
    "PAGEIND", "PETRONET", "PIIND", "POLYCAB", "PNB",
    "RECLTD", "SAIL", "SHRIRAMFIN", "SIEMENS", "SRF",
    "TATAPOWER", "TORNTPHARM", "TRENT", "VEDL", "ZOMATO",
    # NIFTY 100-200 (well-known subset)
    "ABCAPITAL", "ABFRL", "ALKEM", "ASHOKLEY", "AUROPHARMA",
    "BALKRISIND", "BANKINDIA", "BHEL", "COLPAL", "CROMPTON",
    "DEEPAKNTRAS", "EMAMILTD", "ESCORTS", "EXIDEIND", "GAIL",
    "GLENMARK", "IPCALAB", "IRFC", "JKCEMENT", "JUBLFOOD",
    "KANSAINER", "LICHSGFIN", "LODHA", "MFSL", "MGL",
    "MPHASIS", "NATCOPHARM", "NATIONALUM", "NYKAA", "OFSS",
    "PFC", "PHOENIXLTD", "PRESTIGE", "PVRINOX", "RAMCOCEM",
    "RBLBANK", "SJVN", "TORNTPOWER", "TVSMOTOR", "VBL",
    "VOLTAS", "WHIRLPOOL", "ZEEL", "CESC", "GMRINFRA",
    "HFCL", "GODREJIND", "DEEPAKNTR", "JUBILANT", "COLGATE",
]


# ── symbol / instrument resolution ───────────────────────────────────────────
def fetch_nifty200_symbols() -> list:
    url = "https://niftyindices.com/IndexConstituent/ind_nifty200list.csv"
    try:
        r = requests.get(
            url, timeout=15,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"},
        )
        r.raise_for_status()
        symbols = []
        for line in r.text.strip().splitlines()[1:]:
            parts = line.split(",")
            if len(parts) >= 3:
                sym = parts[2].strip().strip('"')
                if sym:
                    symbols.append(sym)
        if len(symbols) >= 50:
            log.info(f"NSE download OK — {len(symbols)} NIFTY 200 symbols")
            return symbols
        log.warning("NSE returned too few symbols; using fallback list")
    except Exception as exc:
        log.warning(f"NSE download failed ({exc}); using fallback list")
    log.info(f"Fallback list: {len(NIFTY200_FALLBACK)} symbols")
    return list(NIFTY200_FALLBACK)


def build_token_map(kite: KiteConnect, equity_symbols: list, future_bases: list) -> dict:
    """Returns { symbol: (instrument_token, exchange) }"""
    log.info("Downloading Zerodha instruments list ...")
    instruments = kite.instruments()
    log.info(f"  {len(instruments):,} instruments received")

    nse_eq = {
        i["tradingsymbol"]: i["instrument_token"]
        for i in instruments
        if i["exchange"] == "NSE" and i["instrument_type"] == "EQ"
    }

    today   = datetime.date.today()
    nfo_fut = [i for i in instruments if i["exchange"] == "NFO" and i["instrument_type"] == "FUT"]

    fut_tokens = {}
    for base in future_bases:
        candidates = [i for i in nfo_fut if i["name"] == base]
        if not candidates:
            log.warning(f"  No futures contract found for {base}")
            continue
        upcoming = [c for c in candidates if c["expiry"] >= today] or candidates
        near     = min(upcoming, key=lambda x: x["expiry"])
        key      = f"{base}_FUT"
        fut_tokens[key] = (near["instrument_token"], "NFO")
        log.info(f"  {key:20s} → token={near['instrument_token']}  expiry={near['expiry']}")

    token_map = {}
    missing   = []
    for sym in equity_symbols:
        if sym in nse_eq:
            token_map[sym] = (nse_eq[sym], "NSE")
        else:
            missing.append(sym)

    token_map.update(fut_tokens)

    if missing:
        log.warning(f"  {len(missing)} equity symbols not resolved: {missing}")
    log.info(f"  Token map ready — {len(token_map)} symbols ({len(fut_tokens)} futures)")
    return token_map


# ── fetch candles from Zerodha ────────────────────────────────────────────────
def _to_utc(dt: datetime.datetime) -> datetime.datetime:
    if dt.tzinfo is None:
        dt = IST.localize(dt)
    return dt.astimezone(pytz.utc)


def fetch_symbol(kite: KiteConnect, symbol: str, token: int,
                 from_dt: datetime.date, to_dt: datetime.date) -> list:
    candles  = []
    cursor   = from_dt

    while cursor < to_dt:
        chunk_end = min(cursor + datetime.timedelta(days=CHUNK_DAYS), to_dt)
        attempts  = 0

        while attempts < 3:
            try:
                chunk = kite.historical_data(
                    instrument_token=token,
                    from_date=cursor,
                    to_date=chunk_end,
                    interval="minute",
                    continuous=False,
                    oi=False,
                )
                candles.extend(chunk)
                log.debug(f"    {cursor} → {chunk_end}: {len(chunk)} candles")
                break

            except Exception as exc:
                msg = str(exc).lower()
                if "too many requests" in msg or "429" in msg or "rate" in msg:
                    log.warning("  Rate-limited — sleeping 30s ...")
                    time.sleep(30)
                elif any(k in msg for k in ("token", "invalid", "forbidden", "unauthorized")):
                    log.error(
                        "Zerodha access token rejected.\n"
                        "  → Reconnect Zerodha in Admin UI → Broker Infrastructure."
                    )
                    sys.exit(1)
                else:
                    log.warning(f"  Chunk {cursor}→{chunk_end} error (attempt {attempts+1}): {exc}")
                    time.sleep(2 ** (attempts + 1))
                attempts += 1

        cursor = chunk_end + datetime.timedelta(days=1)
        time.sleep(0.35)   # ~2.8 req/s — safely under Zerodha's 120 req/min

    return candles


# ── upsert into marketdata_candles ────────────────────────────────────────────
_UPSERT_SQL = """
INSERT INTO marketdata_candles
    (id, created_at, updated_at, version, deleted,
     symbol, timeframe, open_time,
     open_price, high_price, low_price, close_price, volume)
VALUES %s
ON CONFLICT (symbol, timeframe, open_time) WHERE (deleted = false)
DO UPDATE SET
    open_price  = EXCLUDED.open_price,
    high_price  = EXCLUDED.high_price,
    low_price   = EXCLUDED.low_price,
    close_price = EXCLUDED.close_price,
    volume      = EXCLUDED.volume,
    updated_at  = EXCLUDED.updated_at,
    version     = marketdata_candles.version + 1
"""


def upsert_candles(conn, symbol: str, candles: list) -> int:
    if not candles:
        return 0
    now  = datetime.datetime.now(tz=pytz.utc)
    rows = [
        (
            str(uuid.uuid4()), now, now, 0, False,
            symbol, TIMEFRAME,
            _to_utc(c["date"]),
            c["open"], c["high"], c["low"], c["close"],
            c.get("volume") or 0,
        )
        for c in candles
    ]
    with conn.cursor() as cur:
        psycopg2.extras.execute_values(cur, _UPSERT_SQL, rows, page_size=500)
    conn.commit()
    return len(rows)


# ── truncate all market-data tables ──────────────────────────────────────────
def truncate_all(conn) -> None:
    print()
    print("WARNING: This will DELETE ALL ROWS from:")
    print("  marketdata_candles, market_data_coverage, market_backfill_jobs,")
    print("  market_backfill_job_symbols, market_backfill_gaps, market_backfill_failures")
    print()
    confirm = input("Type  YES  to confirm: ").strip()
    if confirm != "YES":
        log.info("Aborted — no data deleted.")
        return
    with conn.cursor() as cur:
        cur.execute("""
            TRUNCATE TABLE
                market_backfill_failures,
                market_backfill_gaps,
                market_backfill_job_symbols,
                market_backfill_jobs,
                market_data_coverage,
                marketdata_candles
            RESTART IDENTITY CASCADE
        """)
    conn.commit()
    log.info("All market-data tables truncated.")


# ── seed run ──────────────────────────────────────────────────────────────────
def run_seed(kite: KiteConnect, conn, token_map: dict, days: int = SEED_DAYS) -> None:
    to_dt   = datetime.date.today()
    from_dt = to_dt - datetime.timedelta(days=days)
    total   = 0
    n       = len(token_map)

    log.info("=" * 64)
    log.info(f"Seed start | {n} symbols | {from_dt} → {to_dt} | timeframe={TIMEFRAME}")
    log.info("=" * 64)

    for idx, (symbol, (token, exchange)) in enumerate(token_map.items(), 1):
        pct = idx * 100 // n
        log.info(f"[{idx:>3}/{n}  {pct:>3}%]  {symbol}  ({exchange})")
        candles = fetch_symbol(kite, symbol, token, from_dt, to_dt)
        count   = upsert_candles(conn, symbol, candles)
        total  += count
        log.info(f"           → {count:>7,} candles  (running total: {total:,})")

    log.info("=" * 64)
    log.info(f"Seed complete | {total:,} candles | {n} symbols")
    log.info("=" * 64)


def run_daily(kite: KiteConnect, conn, equity_symbols: list) -> None:
    """Daily daemon callback — re-loads token from DB (handles daily token renewal)."""
    log.info("Daily refresh triggered — loading fresh Zerodha token from DB ...")
    access_token = load_zerodha_token(conn)
    kite.set_access_token(access_token)

    # Re-resolve instrument map (near-month futures contract changes at expiry)
    token_map = build_token_map(kite, equity_symbols, FUTURES_BASES)
    run_seed(kite, conn, token_map, days=2)   # 2 days covers yesterday + any lag


# ── main ──────────────────────────────────────────────────────────────────────
def main() -> None:
    parser = argparse.ArgumentParser(
        description="stokr market data seeder — Zerodha 1-min candles → PostgreSQL"
    )
    parser.add_argument(
        "--mode",
        choices=["seed", "daemon", "truncate"],
        default="seed",
        help="seed=one-time 60-day fill | daemon=seed+daily refresh | truncate=wipe tables",
    )
    args = parser.parse_args()

    log.info(f"Connecting to DB: {DB_URL.split('@')[-1]}")   # hide credentials in log
    conn = psycopg2.connect(DB_URL)

    if args.mode == "truncate":
        truncate_all(conn)
        conn.close()
        return

    if not API_KEY:
        log.error("ZERODHA_API_KEY (or STOKR_ZERODHA_API_KEY) is not set.")
        sys.exit(1)

    # Load token from DB — no manual copy required
    log.info("Loading Zerodha access token from database ...")
    access_token = load_zerodha_token(conn)

    kite = KiteConnect(api_key=API_KEY)
    kite.set_access_token(access_token)
    log.info("Zerodha session ready.")

    equity_symbols = fetch_nifty200_symbols()
    token_map      = build_token_map(kite, equity_symbols, FUTURES_BASES)

    if args.mode == "seed":
        run_seed(kite, conn, token_map)
        conn.close()

    elif args.mode == "daemon":
        run_seed(kite, conn, token_map)   # initial fill

        # 16:15 IST = 10:45 UTC  (NSE closes 15:30, 45-min settle buffer)
        # If running on an IST machine, change "10:45" → "16:15"
        schedule.every().day.at("10:45").do(run_daily, kite, conn, equity_symbols)
        log.info("Daemon running. Daily candle refresh at 16:15 IST (10:45 UTC). Ctrl-C to stop.")
        while True:
            schedule.run_pending()
            time.sleep(30)


if __name__ == "__main__":
    main()
