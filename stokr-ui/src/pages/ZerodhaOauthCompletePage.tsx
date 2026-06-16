import { useEffect, useState } from "react";
import { CheckCircle2, Loader2, XCircle } from "lucide-react";
import {
  ZERODHA_OAUTH_STORAGE_KEY,
  ZERODHA_PLATFORM_FEED_OAUTH_MESSAGE,
  ZERODHA_TRADER_OAUTH_MESSAGE,
} from "../lib/zerodhaOAuthMessages";

/**
 * Lightweight OAuth return target (no app shell). Notifies opener and closes the popup.
 */
export function ZerodhaOauthCompletePage() {
  const [status, setStatus] = useState<"pending" | "ok" | "error">("pending");
  const [context, setContext] = useState<"trader" | "platform_feed">("trader");
  const [reasonText, setReasonText] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const platformFeed = params.get("platform_feed");
    const trader = params.get("zerodha");
    const reason = params.get("reason");
    setReasonText(reason);

    let messageType = ZERODHA_TRADER_OAUTH_MESSAGE;
    let result: "ok" | "error" = "error";

    if (platformFeed === "ok" || platformFeed === "error") {
      setContext("platform_feed");
      messageType = ZERODHA_PLATFORM_FEED_OAUTH_MESSAGE;
      result = platformFeed === "ok" ? "ok" : "error";
    } else if (trader === "ok" || trader === "error") {
      setContext("trader");
      messageType = ZERODHA_TRADER_OAUTH_MESSAGE;
      result = trader === "ok" ? "ok" : "error";
    }

    setStatus(result);

    const payload = { type: messageType, status: result, reason };

    if (window.opener && !window.opener.closed) {
      try {
        window.opener.postMessage(payload, "*");
      } catch {
        /* ignore */
      }
    }

    try {
      localStorage.setItem(
        ZERODHA_OAUTH_STORAGE_KEY,
        JSON.stringify({ ...payload, at: Date.now() }),
      );
    } catch {
      /* ignore */
    }

    const closeTimer = window.setTimeout(() => {
      window.close();
    }, 400);

    return () => window.clearTimeout(closeTimer);
  }, []);

  const okTitle =
    context === "platform_feed" ? "Platform feed linked successfully" : "Zerodha linked successfully";
  const errTitle =
    context === "platform_feed" ? "Platform feed linking failed" : "Zerodha linking failed";
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-[#070b12] px-6 text-center text-slate-100">
      {status === "pending" && (
        <>
          <Loader2 className="h-10 w-10 animate-spin text-cyan-400" />
          <p className="text-sm text-slate-300">Completing Zerodha connection…</p>
        </>
      )}
      {status === "ok" && (
        <>
          <CheckCircle2 className="h-10 w-10 text-emerald-400" />
          <p className="text-sm font-medium text-emerald-200">{okTitle}</p>
          <p className="text-xs text-slate-400">This window will close automatically.</p>
        </>
      )}
      {status === "error" && (
        <>
          <XCircle className="h-10 w-10 text-rose-400" />
          <p className="text-sm font-medium text-rose-200">{errTitle}</p>
          {reasonText ? (
            <p className="max-w-md text-xs leading-5 text-rose-200/80">{reasonText}</p>
          ) : null}
          <p className="text-xs text-slate-400">Close this window and try Connect again.</p>
        </>
      )}
    </div>
  );
}
