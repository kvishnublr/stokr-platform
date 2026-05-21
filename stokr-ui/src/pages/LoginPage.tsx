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
    <div className="relative min-h-screen overflow-hidden bg-[#f4f6f8]">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_top,_rgba(59,130,246,0.16),transparent_55%),radial-gradient(ellipse_at_bottom,_rgba(148,163,184,0.18),transparent_50%)]" />
      <div className="relative flex min-h-screen items-center justify-center px-4 py-16">
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.35 }}
          className="w-full max-w-md"
        >
          <div className="mb-8 text-center">
            <div className="text-3xl font-semibold tracking-tight text-neutral-900">Stokr</div>
            <p className="mt-2 text-sm text-neutral-600">Institutional-grade execution console</p>
          </div>

          <div className="rounded-2xl border border-neutral-200 bg-white/95 p-8 shadow-xl backdrop-blur">
            <form className="space-y-5" onSubmit={onSubmit}>
              <div>
                <label className="block text-xs font-medium uppercase tracking-wide text-neutral-500">
                  Email or username
                </label>
                <div className="relative mt-2">
                  <Mail className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-neutral-500" />
                  <input
                    className="w-full rounded-lg border border-neutral-200 bg-white py-2 pl-10 pr-3 text-sm text-neutral-900 outline-none ring-blue-500/30 placeholder:text-neutral-400 focus:border-blue-500/60 focus:ring-2"
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
                    className="w-full rounded-lg border border-neutral-200 bg-white py-2 pl-10 pr-3 text-sm text-neutral-900 outline-none ring-blue-500/30 focus:border-blue-500/60 focus:ring-2"
                    type="password"
                    placeholder="************"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    autoComplete="current-password"
                  />
                </div>
              </div>

              <div className="flex justify-end">
                <Link
                  className="text-sm font-medium text-blue-600 hover:text-blue-500"
                  to="/forgot-password"
                >
                  Forgot password?
                </Link>
              </div>

              {error ? <div className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

              <button
                disabled={loading}
                type="submit"
                className={cn(
                  "flex w-full items-center justify-center gap-2 rounded-lg bg-neutral-900 py-2.5 text-sm font-semibold text-white transition hover:bg-neutral-800",
                  loading && "opacity-60",
                )}
              >
                {loading ? "Signing in..." : "Sign in"}
                {!loading ? <ArrowRight className="h-4 w-4" /> : null}
              </button>

              <p className="text-center text-sm text-neutral-500">
                New here?{" "}
                <Link className="font-medium text-blue-600 hover:text-blue-500" to="/register">
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
// deploy-test-1779345062
// redeploy-1779345391
// redeploy-1779346090
