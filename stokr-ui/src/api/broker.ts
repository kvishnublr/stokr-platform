import { api } from "./client";

/** TanStack Query key for Zerodha broker status (trader-scoped). */
export const BROKER_STATUS_QUERY_KEY = ["trader-broker-status"] as const;

export type BrokerStatusDto = {
  connected: boolean;
  brokerName: string;
  tokenValid: boolean;
  lastSyncAt: string | null;
  accountId: string | null;
  profileUserName: string | null;
  profileEmail: string | null;
  marginSummary: string | null;
  health: string;
  testOrderEnabled: boolean;
  testOrderDryRun: boolean;
  testTradeDisabledReason: string | null;
};

export type BrokerTestConnectionDto = {
  ok: boolean;
  message: string;
  profileUserName: string | null;
  marginSummary: string | null;
};

export type BrokerTestOrderDto = {
  dryRun: boolean;
  orderId: string;
  status: string;
  message: string;
  rawStatus: string;
};

export type BrokerTestOrderPayload = {
  variety: "REGULAR" | "AMO";
  exchange: "NSE" | "BSE";
  tradingsymbol: string;
  side: "BUY" | "SELL";
  quantity: number;
  orderType: "MARKET";
  product: "CNC" | "MIS";
};

export async function fetchBrokerStatus(): Promise<BrokerStatusDto> {
  const res = await api.get("/api/trader/broker/status");
  const data = res.data?.data;
  if (!data || typeof data !== "object") {
    throw new Error("Unexpected broker status response");
  }
  return data as BrokerStatusDto;
}

export async function postBrokerTestConnection(): Promise<BrokerTestConnectionDto> {
  const res = await api.post("/api/trader/broker/test-connection");
  const data = res.data?.data;
  if (!data || typeof data !== "object") {
    throw new Error("Unexpected test-connection response");
  }
  return data as BrokerTestConnectionDto;
}

export async function postBrokerDisconnect(): Promise<void> {
  await api.post("/api/trader/broker/disconnect");
}

export async function postBrokerTestOrder(body: BrokerTestOrderPayload): Promise<BrokerTestOrderDto> {
  const res = await api.post("/api/trader/broker/test-order", body);
  const data = res.data?.data;
  if (!data || typeof data !== "object") {
    throw new Error("Unexpected test-order response");
  }
  return data as BrokerTestOrderDto;
}

/** Ensures we only navigate the OAuth popup to Kite (avoids opening same-origin URLs that would postMessage+close instantly). */
function parseZerodhaAuthorizeUrl(raw: string): string {
  const trimmed = typeof raw === "string" ? raw.trim() : "";
  if (!trimmed) {
    throw new Error("Missing authorize URL from server");
  }
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    throw new Error("Invalid authorize URL from server");
  }
  if (parsed.protocol !== "https:") {
    throw new Error("Invalid authorize URL from server");
  }
  if (parsed.hostname.toLowerCase() !== "kite.zerodha.com") {
    throw new Error("Invalid authorize URL from server");
  }
  return parsed.toString();
}

export async function fetchZerodhaConnectUrl(): Promise<string> {
  const res = await api.get("/api/trader/broker/zerodha/connect-url");
  const data = res.data?.data as { authorizeUrl?: string } | undefined;
  const url = data?.authorizeUrl;
  if (!url || typeof url !== "string") {
    throw new Error("Missing authorize URL from server");
  }
  return parseZerodhaAuthorizeUrl(url);
}

/** Client-side guard matching server test-order tradingsymbol rules. */
export function validateTestTradingsymbol(raw: string): string | null {
  const s = raw.trim().toUpperCase();
  if (!/^[A-Z0-9]{1,25}$/.test(s)) {
    return "Symbol must be 1–25 letters or digits (NSE-style).";
  }
  return null;
}
