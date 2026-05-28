/** postMessage type for trader broker OAuth completion (popup → opener). */
export const ZERODHA_TRADER_OAUTH_MESSAGE = "stokr-zerodha-oauth";

/** postMessage type for platform feed OAuth completion (popup → opener). */
export const ZERODHA_PLATFORM_FEED_OAUTH_MESSAGE = "stokr-platform-feed-oauth";

export const ZERODHA_OAUTH_STORAGE_KEY = "stokr_zerodha_oauth_result";

/** www vs apex (or port) mismatch breaks strict origin checks; still require same host+protocol as this tab. */
export function isTrustedZerodhaOauthMessageOrigin(origin: string): boolean {
  if (origin === window.location.origin) return true;
  try {
    const here = new URL(window.location.origin);
    const there = new URL(origin);
    if (here.protocol !== there.protocol) return false;
    if (here.port !== there.port) return false;
    const stripWww = (h: string) => h.toLowerCase().replace(/^www\./, "");
    return stripWww(here.hostname) === stripWww(there.hostname);
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
  return parsed.toString();
}
