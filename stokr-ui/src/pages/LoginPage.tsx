import { FormEvent, useState } from "react";
import { Link, Navigate } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowRight, Lock, Mail } from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";
import type { AuthPayload } from "../state/session";
import { useSessionStore } from "../state/session";

const containerVariants = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: { staggerChildren: 0.07, delayChildren: 0.08 },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 12 },
  show: { opacity: 1, y: 0, transition: { duration: 0.4, ease: [0.22, 1, 0.36, 1] } },
};

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
    <div className="relative min-h-screen overflow-hidden bg-background">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_50%_30%_at_50%_10%,oklch(0.62_0.19_258/0.07),transparent_70%),radial-gradient(ellipse_50%_40%_at_50%_90%,oklch(0.55_0.2_258/0.04),transparent_70%)]" />
      <div className="relative flex min-h-screen items-center justify-center px-4 py-16">
        <motion.div
          initial={{ opacity: 0, y: 16, filter: "blur(6px)" }}
          animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
          transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
          className="w-full max-w-sm"
        >
          <div className="mb-10 text-center">
            <motion.div
              initial={{ opacity: 0, scale: 0.92 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ duration: 0.4, ease: [0.22, 1, 0.36, 1] }}
              className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-accent/10"
            >
              <span className="text-xl font-bold tracking-tight text-accent">S</span>
            </motion.div>
            <h1 className="text-2xl font-semibold tracking-tight text-foreground">Stokr</h1>
            <p className="mt-1.5 text-sm text-muted-foreground">Institutional-grade execution console</p>
          </div>

          <motion.div
            variants={containerVariants}
            initial="hidden"
            animate="show"
            className="rounded-xl border border-border bg-card p-6"
          >
            <form className="space-y-4" onSubmit={onSubmit}>
              <motion.div variants={itemVariants}>
                <label className="block text-xs font-medium uppercase tracking-wider text-muted-foreground">
                  Email or username
                </label>
                <div className="relative mt-1.5">
                  <Mail className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground/60" />
                  <input
                    className="w-full rounded-lg border border-border bg-background/50 py-2 pl-10 pr-3 text-sm text-foreground outline-none ring-accent/30 placeholder:text-muted-foreground/40 focus:border-accent/60 focus:ring-2 transition-[border-color,box-shadow] duration-200"
                    placeholder="you@company.com"
                    value={principal}
                    onChange={(e) => setPrincipal(e.target.value)}
                    autoComplete="username"
                  />
                </div>
              </motion.div>

              <motion.div variants={itemVariants}>
                <label className="block text-xs font-medium uppercase tracking-wider text-muted-foreground">
                  Password
                </label>
                <div className="relative mt-1.5">
                  <Lock className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground/60" />
                  <input
                    className="w-full rounded-lg border border-border bg-background/50 py-2 pl-10 pr-3 text-sm text-foreground outline-none ring-accent/30 placeholder:text-muted-foreground/40 focus:border-accent/60 focus:ring-2 transition-[border-color,box-shadow] duration-200"
                    type="password"
                    placeholder="&#x2022;&#x2022;&#x2022;&#x2022;&#x2022;&#x2022;&#x2022;&#x2022;"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    autoComplete="current-password"
                  />
                </div>
              </motion.div>

              <motion.div variants={itemVariants} className="flex justify-end">
                <Link
                  className="text-xs font-medium text-accent/80 hover:text-accent transition-colors duration-200"
                  to="/forgot-password"
                >
                  Forgot password?
                </Link>
              </motion.div>

              {error ? (
                <motion.div
                  initial={{ opacity: 0, y: -4 }}
                  animate={{ opacity: 1, y: 0 }}
                  className="rounded-lg bg-risk-high/10 px-3 py-2 text-xs text-risk-high"
                >
                  {error}
                </motion.div>
              ) : null}

              <motion.button
                variants={itemVariants}
                whileHover={{ scale: 1.01 }}
                whileTap={{ scale: 0.99 }}
                disabled={loading}
                type="submit"
                className={cn(
                  "flex w-full items-center justify-center gap-2 rounded-lg bg-foreground py-2.5 text-sm font-semibold text-background transition-all duration-200 hover:opacity-90",
                  loading && "opacity-60",
                )}
              >
                {loading ? "Signing in..." : "Sign in"}
                {!loading ? <ArrowRight className="h-4 w-4" /> : null}
              </motion.button>

              <motion.p variants={itemVariants} className="text-center text-xs text-muted-foreground">
                New here?{" "}
                <Link className="font-medium text-accent/80 hover:text-accent transition-colors duration-200" to="/register">
                  Create an account
                </Link>
              </motion.p>
            </form>
          </motion.div>
        </motion.div>
      </div>
    </div>
  );
}
