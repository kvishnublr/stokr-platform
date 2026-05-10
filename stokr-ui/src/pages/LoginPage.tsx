import { FormEvent, useState } from "react";
import { Link, Navigate } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowRight, Lock, Mail } from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";
import type { AuthPayload } from "../state/session";
import { useSessionStore } from "../state/session";

export function LoginPage() {
  const token = useSessionStore((s) => s.accessToken);
  const setSession = useSessionStore((s) => s.setSession);

  const [principal, setPrincipal] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  if (token) {
    return <Navigate to="/" replace />;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const res = await api.post("/api/auth/login", { principal, password });
      const d = res.data?.data as Partial<AuthPayload> | undefined;
      if (!d?.accessToken || !d.refreshToken || !d.userId || !d.username || !d.email) {
        throw new Error("Unexpected response");
      }
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
    } catch (err: unknown) {
      setError(parseAxiosMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="relative min-h-screen overflow-hidden bg-neutral-950">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_top,_rgba(59,130,246,0.15),transparent_55%),radial-gradient(ellipse_at_bottom,_rgba(168,85,247,0.12),transparent_50%)]" />
      <div className="relative flex min-h-screen items-center justify-center px-4 py-16">
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.35 }}
          className="w-full max-w-md"
        >
          <div className="mb-8 text-center">
            <div className="text-3xl font-semibold tracking-tight text-white">Stokr</div>
            <p className="mt-2 text-sm text-neutral-400">Institutional-grade execution console</p>
          </div>

          <div className="rounded-2xl border border-neutral-800 bg-neutral-950/80 p-8 shadow-2xl backdrop-blur">
            <form className="space-y-5" onSubmit={onSubmit}>
              <div>
                <label className="block text-xs font-medium uppercase tracking-wide text-neutral-500">
                  Email or username
                </label>
                <div className="relative mt-2">
                  <Mail className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-neutral-500" />
                  <input
                    className="w-full rounded-lg border border-neutral-800 bg-neutral-900/80 py-2 pl-10 pr-3 text-sm text-white outline-none ring-blue-500/40 placeholder:text-neutral-600 focus:border-blue-500/50 focus:ring-2"
                    placeholder="you@company.com"
                    value={principal}
                    onChange={(e) => setPrincipal(e.target.value)}
                    autoComplete="username"
                  />
                </div>
              </div>
              <div>
                <label className="block text-xs font-medium uppercase tracking-wide text-neutral-500">
                  Password
                </label>
                <div className="relative mt-2">
                  <Lock className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-neutral-500" />
                  <input
                    className="w-full rounded-lg border border-neutral-800 bg-neutral-900/80 py-2 pl-10 pr-3 text-sm text-white outline-none ring-blue-500/40 focus:border-blue-500/50 focus:ring-2"
                    type="password"
                    placeholder="••••••••••••"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    autoComplete="current-password"
                  />
                </div>
              </div>

              {error ? <div className="rounded-lg bg-red-950/50 px-3 py-2 text-sm text-red-300">{error}</div> : null}

              <button
                disabled={loading}
                type="submit"
                className={cn(
                  "flex w-full items-center justify-center gap-2 rounded-lg bg-white py-2.5 text-sm font-semibold text-neutral-950 transition hover:bg-neutral-200",
                  loading && "opacity-60",
                )}
              >
                {loading ? "Signing in…" : "Sign in"}
                {!loading ? <ArrowRight className="h-4 w-4" /> : null}
              </button>

              <p className="text-center text-sm text-neutral-500">
                New here?{" "}
                <Link className="font-medium text-blue-400 hover:text-blue-300" to="/register">
                  Create an account
                </Link>
              </p>
            </form>
          </div>
        </motion.div>
      </div>
    </div>
  );
}
