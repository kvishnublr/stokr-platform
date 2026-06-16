/** postMessage type for trader broker OAuth completion (popup → opener). */
export const ZERODHA_TRADER_OAUTH_MESSAGE = "stokr-zerodha-oauth";

/** postMessage type for platform feed OAuth completion (popup → opener). */
export const ZERODHA_PLATFORM_FEED_OAUTH_MESSAGE = "stokr-platform-feed-oauth";

export const ZERODHA_OAUTH_STORAGE_KEY = "stokr_zerodha_oauth_result";

/** Hostnames allowed to postMessage OAuth completion (IP vs domain vs localhost dev). */
const TRUSTED_OAUTH_COMPLETION_HOSTS = new Set([
  "stokr.in",
  "173.249.55.84",
  "localhost",
  "127.0.0.1",
]);

function normalizeHost(hostname: string): string {
  return hostname.toLowerCase().replace(/^www\./, "");
}

/** Accept same tab origin, or known Stokr deployment hosts (IP access + https domain share one server). */
export function isTrustedZerodhaOauthMessageOrigin(origin: string): boolean {
  if (origin === window.location.origin) return true;
  try {
    const there = new URL(origin);
    const host = normalizeHost(there.hostname);
    if (TRUSTED_OAUTH_COMPLETION_HOSTS.has(host)) return true;
    const here = new URL(window.location.origin);
    return normalizeHost(here.hostname) === host;
  } catch {
    return false;
  }
}

export function parseZerodhaAuthorizeUrl(raw: string): string {
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
  if (parsed.searchParams.has("redirect_url")) {
    const state = parsed.searchParams.get("state");
    parsed.searchParams.delete("redirect_url");
    parsed.searchParams.delete("state");
    if (state && !parsed.searchParams.has("redirect_params")) {
      parsed.searchParams.set("redirect_params", `state=${state}`);
    }
  }
  return parsed.toString();
}
