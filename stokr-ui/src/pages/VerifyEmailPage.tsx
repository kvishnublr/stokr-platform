import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { motion } from "framer-motion";
import { CheckCircle2, XCircle } from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import type { AuthPayload } from "../state/session";
import { useSessionStore } from "../state/session";
import { cn } from "../lib/utils";

type Status = "loading" | "ok" | "err";

export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get("token");
  const navigate = useNavigate();
  const setSession = useSessionStore((s) => s.setSession);
  const [status, setStatus] = useState<Status>("loading");
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      setStatus("err");
      setMessage("Missing verification token in the link.");
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const res = await api.get("/api/auth/verify-email", { params: { token } });
        const d = res.data?.data as Partial<AuthPayload> | undefined;
        if (!d?.accessToken || !d.refreshToken || !d.userId || !d.username || !d.email) {
          throw new Error("Unexpected response");
        }
        if (cancelled) return;
        setSession({
          accessToken: d.accessToken,
          refreshToken: d.refreshToken,
          userId: String(d.userId),
          username: d.username,
          email: d.email,
          displayName: d.displayName ?? null,
          roles: Array.isArray(d.roles) ? d.roles : [],
          expiresInSeconds: d.expiresInSeconds ?? 0,
          emailVerified: Boolean(d.emailVerified),
          telegramVerified: Boolean(d.telegramVerified),
          whatsAppVerified: Boolean(
            d.whatsAppVerified ?? (d as { whatsappVerified?: boolean }).whatsappVerified,
          ),
          onboardingComplete: Boolean(d.onboardingComplete),
          liveTradingApproved: Boolean(d.liveTradingApproved),
        });
        setStatus("ok");
        setTimeout(() => navigate("/", { replace: true }), 1200);
      } catch (err: unknown) {
        if (cancelled) return;
        setStatus("err");
        setMessage(parseAxiosMessage(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, navigate, setSession]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-neutral-950 px-4 text-neutral-100">
      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        className="w-full max-w-md rounded-2xl border border-neutral-800 bg-neutral-900/60 p-8 shadow-2xl backdrop-blur"
      >
        {status === "loading" ? (
          <div className="text-center text-sm text-neutral-400">Verifying your email...</div>
        ) : null}
        {status === "ok" ? (
          <div className="text-center">
            <CheckCircle2 className="mx-auto h-10 w-10 text-emerald-500" />
            <h1 className="mt-4 text-lg font-semibold text-white">Email verified</h1>
            <p className="mt-2 text-sm text-neutral-400">Redirecting to your workspace...</p>
          </div>
        ) : null}
        {status === "err" ? (
          <div className="text-center">
            <XCircle className="mx-auto h-10 w-10 text-rose-500" />
            <h1 className="mt-4 text-lg font-semibold text-white">Verification failed</h1>
            <p className={cn("mt-2 text-sm", message ? "text-rose-200" : "text-neutral-500")}>
              {message ?? "The link is invalid or has expired. Request a new one from the app."}
            </p>
            <div className="mt-6 flex flex-col gap-2 text-sm">
              <Link
                to="/login"
                className="rounded-lg border border-neutral-700 py-2 text-neutral-200 transition hover:bg-neutral-800"
              >
                Back to sign in
              </Link>
            </div>
          </div>
        ) : null}
      </motion.div>
    </div>
  );
}
