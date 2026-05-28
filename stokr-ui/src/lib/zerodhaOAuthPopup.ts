import {
  isTrustedZerodhaOauthMessageOrigin,
  parseZerodhaAuthorizeUrl,
  ZERODHA_OAUTH_STORAGE_KEY,
  ZERODHA_PLATFORM_FEED_OAUTH_MESSAGE,
  ZERODHA_TRADER_OAUTH_MESSAGE,
} from "./zerodhaOAuthMessages";

export type ZerodhaOAuthMessageType =
  | typeof ZERODHA_TRADER_OAUTH_MESSAGE
  | typeof ZERODHA_PLATFORM_FEED_OAUTH_MESSAGE;

export type ZerodhaOAuthPopupResult = {
  status: "ok" | "error";
  reason?: string | null;
};

type OpenZerodhaOAuthPopupOptions = {
  authorizeUrl: string;
  messageType: ZerodhaOAuthMessageType;
  popupName?: string;
  onComplete: (result: ZerodhaOAuthPopupResult) => void;
  /** Called when popup closes before a completion message (user cancelled). */
  onEarlyClose?: () => void;
};

const POPUP_FEATURES =
  "popup=yes,width=560,height=820,scrollbars=yes,resizable=yes,status=no,toolbar=no,menubar=no,location=yes";

/**
 * Opens Kite OAuth in a popup and resolves when the completion page postMessages the opener.
 * Do not pass noopener — the completion route uses window.opener.postMessage.
 */
export function openZerodhaOAuthPopup({
  authorizeUrl,
  messageType,
  popupName = "stokr_zerodha_oauth",
  onComplete,
  onEarlyClose,
}: OpenZerodhaOAuthPopupOptions): { blocked: boolean; cancel: () => void } {
  let handled = false;
  let pollRef: ReturnType<typeof setInterval> | null = null;
  let graceRef: ReturnType<typeof setTimeout> | null = null;

  const finish = (result: ZerodhaOAuthPopupResult) => {
    if (handled) return;
    handled = true;
    tearDown();
    onComplete(result);
  };

  const tearDown = () => {
    window.removeEventListener("message", onMessage);
    window.removeEventListener("storage", onStorage);
    if (pollRef) {
      clearInterval(pollRef);
      pollRef = null;
    }
    if (graceRef) {
      clearTimeout(graceRef);
      graceRef = null;
    }
  };

  const onMessage = (ev: MessageEvent) => {
    if (!isTrustedZerodhaOauthMessageOrigin(ev.origin)) return;
    const data = ev.data as { type?: string; status?: string; reason?: string } | undefined;
    if (!data || data.type !== messageType) return;
    finish({
      status: data.status === "ok" ? "ok" : "error",
      reason: data.reason ?? null,
    });
  };

  const onStorage = (ev: StorageEvent) => {
    if (ev.key !== ZERODHA_OAUTH_STORAGE_KEY || !ev.newValue) return;
    try {
      const data = JSON.parse(ev.newValue) as { type?: string; status?: string; reason?: string };
      if (data.type !== messageType) return;
      finish({
        status: data.status === "ok" ? "ok" : "error",
        reason: data.reason ?? null,
      });
      localStorage.removeItem(ZERODHA_OAUTH_STORAGE_KEY);
    } catch {
      /* ignore */
    }
  };

  let popup: Window | null;
  try {
    const url = parseZerodhaAuthorizeUrl(authorizeUrl);
    popup = window.open(url, popupName, POPUP_FEATURES);
  } catch {
    return { blocked: true, cancel: tearDown };
  }

  if (!popup) {
    return { blocked: true, cancel: tearDown };
  }

  window.addEventListener("message", onMessage);
  window.addEventListener("storage", onStorage);

  pollRef = setInterval(() => {
    if (!popup?.closed) return;
    if (graceRef) return;
    if (pollRef) {
      clearInterval(pollRef);
      pollRef = null;
    }
    graceRef = setTimeout(() => {
      graceRef = null;
      if (handled) return;
      tearDown();
      onEarlyClose?.();
    }, 750);
  }, 500);

  return { blocked: false, cancel: tearDown };
}
