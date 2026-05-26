import { useEffect, useState } from "react";
import { CheckCircle2, Loader2, XCircle } from "lucide-react";

const ZERODHA_OAUTH_MESSAGE = "stokr-zerodha-oauth";

/**
 * Lightweight OAuth return target (no app shell). Notifies opener and closes the popup.
 */
export function ZerodhaOauthCompletePage() {
  const [status, setStatus] = useState<"pending" | "ok" | "error">("pending");

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const z = params.get("zerodha");
    const result = z === "ok" ? "ok" : z === "error" ? "error" : "error";
    setStatus(result);

    const payload = { type: ZERODHA_OAUTH_MESSAGE, status: result };

    if (window.opener && !window.opener.closed) {
      try {
        window.opener.postMessage(payload, "*");
      } catch {
        /* ignore */
      }
    }

    try {
      localStorage.setItem("stokr_zerodha_oauth_result", JSON.stringify({ ...payload, at: Date.now() }));
    } catch {
      /* ignore */
    }

    const closeTimer = window.setTimeout(() => {
      window.close();
    }, 400);

    return () => window.clearTimeout(closeTimer);
  }, []);

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
          <p className="text-sm font-medium text-emerald-200">Zerodha linked successfully</p>
          <p className="text-xs text-slate-400">This window will close automatically.</p>
        </>
      )}
      {status === "error" && (
        <>
          <XCircle className="h-10 w-10 text-rose-400" />
          <p className="text-sm font-medium text-rose-200">Zerodha linking failed</p>
          <p className="text-xs text-slate-400">Close this window and try Connect again.</p>
        </>
      )}
    </div>
  );
}
