import { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { ExternalLink, MessageCircle, ShieldCheck } from "lucide-react";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { useSessionStore } from "../state/session";

export function BrokersPage() {
  const emailVerified = useSessionStore((s) => s.emailVerified);
  const [tgLink, setTgLink] = useState<string | null>(null);
  const [zerodhaUrl, setZerodhaUrl] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const z = params.get("zerodha");
    if (z === "ok") toast.success("Zerodha session linked");
    if (z === "error") toast.error("Zerodha linking failed — try again");
  }, []);

  async function requestTelegramLink() {
    try {
      const res = await api.post("/api/trader/integrations/telegram/verification-link");
      const payload = res.data?.data as { deepLink?: string } | undefined;
      const url = payload?.deepLink;
      if (url) {
        setTgLink(url);
        toast.message("Open Telegram to complete verification");
      }
    } catch (e: unknown) {
      toast.error(parseAxiosMessage(e));
    }
  }

  async function openZerodhaConnect() {
    try {
      const res = await api.get("/api/trader/broker/zerodha/authorize-url");
      const payload = res.data?.data as { authorizeUrl?: string } | undefined;
      const url = payload?.authorizeUrl;
      if (url) {
        setZerodhaUrl(url);
        window.location.href = url;
      }
    } catch (e: unknown) {
      toast.error(parseAxiosMessage(e));
    }
  }

  return (
    <div className="space-y-8">
      <div>
        <div className="text-xl font-semibold tracking-tight">Broker connections</div>
        <p className="mt-2 max-w-2xl text-sm text-neutral-400">
          Connect Zerodha via Kite OAuth (encrypted tokens server-side). Telegram verification is required before LIVE
          routing.
        </p>
      </div>

      <motion.div
        initial={{ opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        className="grid gap-6 lg:grid-cols-2"
      >
        <div className="rounded-2xl border border-neutral-800 bg-neutral-900/50 p-6">
          <div className="flex items-center gap-2 text-sm font-semibold text-white">
            <ShieldCheck className="h-4 w-4 text-emerald-400" />
            Zerodha (Kite Connect)
          </div>
          <p className="mt-2 text-xs text-neutral-500">
            Uses OAuth redirect to Zerodha; callback lands on <code className="text-neutral-300">/api/broker/zerodha/callback</code>{" "}
            then returns you here.
          </p>
          {!emailVerified ? (
            <p className="mt-4 text-xs text-amber-200/90">Verify email first (banner on every page).</p>
          ) : (
            <button
              type="button"
              onClick={() => void openZerodhaConnect()}
              className="mt-4 inline-flex items-center gap-2 rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-500"
            >
              Connect Zerodha
              <ExternalLink className="h-4 w-4" />
            </button>
          )}
          {zerodhaUrl ? (
            <p className="mt-3 break-all text-[11px] text-neutral-600">Last URL: {zerodhaUrl}</p>
          ) : null}
        </div>

        <div className="rounded-2xl border border-neutral-800 bg-neutral-900/50 p-6">
          <div className="flex items-center gap-2 text-sm font-semibold text-white">
            <MessageCircle className="h-4 w-4 text-sky-400" />
            Telegram verification
          </div>
          <p className="mt-2 text-xs text-neutral-500">
            Set your @username under profile contact, then generate a deep link and tap{" "}
            <span className="text-neutral-300">Start</span> in the bot.
          </p>
          <button
            type="button"
            onClick={() => void requestTelegramLink()}
            className="mt-4 rounded-lg border border-sky-500/40 px-4 py-2 text-sm font-semibold text-sky-100 hover:bg-sky-500/10"
          >
            Get Telegram link
          </button>
          {tgLink ? (
            <a
              href={tgLink}
              target="_blank"
              rel="noreferrer"
              className="mt-3 block break-all text-xs text-sky-300 underline"
            >
              {tgLink}
            </a>
          ) : null}
        </div>
      </motion.div>
    </div>
  );
}
