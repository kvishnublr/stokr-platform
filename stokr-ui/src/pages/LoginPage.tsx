import { FormEvent, useState } from "react";
import { Link, Navigate } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowRight, Lock, Mail, Sparkles } from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";
import type { AuthPayload } from "../state/session";
import { useSessionStore } from "../state/session";

const containerVariants = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: { staggerChildren: 0.06, delayChildren: 0.1 },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 16 },
  show: { opacity: 1, y: 0, transition: { duration: 0.5, ease: [0.22, 1, 0.36, 1] } },
};

const backdropVariants = {
  animate: {
    backgroundPosition: ["0% 0%", "100% 100%"],
    transition: { duration: 20, repeat: Infinity, repeatType: "reverse" as const },
  },
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
      {/* Animated gradient background */}
      <motion.div
        className="pointer-events-none absolute inset-0"
        variants={backdropVariants}
        animate="animate"
        style={{
          backgroundImage: "linear-gradient(45deg, oklch(0.62 0.19 258/0.08) 0%, oklch(0.55 0.2 258/0.04) 50%, oklch(0.62 0.19 258/0.06) 100%)",
          backgroundSize: "200% 200%",
        }}
      />
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_50%_30%_at_50%_10%,oklch(0.62_0.19_258/0.08),transparent_70%),radial-gradient(ellipse_50%_40%_at_50%_90%,oklch(0.55_0.2_258/0.05),transparent_70%)]" />

      {/* Floating gradient orbs */}
      <motion.div
        className="pointer-events-none absolute -top-40 -left-40 w-80 h-80 rounded-full bg-gradient-to-br from-accent/20 to-transparent blur-3xl"
        animate={{ y: [0, 40, 0], x: [0, 20, 0] }}
        transition={{ duration: 8, repeat: Infinity }}
      />
      <motion.div
        className="pointer-events-none absolute -bottom-40 -right-40 w-80 h-80 rounded-full bg-gradient-to-tl from-accent/15 to-transparent blur-3xl"
        animate={{ y: [0, -40, 0], x: [0, -20, 0] }}
        transition={{ duration: 10, repeat: Infinity }}
      />

      <div className="relative flex min-h-screen items-center justify-center px-4 py-16">
        <motion.div
          initial={{ opacity: 0, y: 20, filter: "blur(8px)" }}
          animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
          transition={{ duration: 0.6, ease: [0.22, 1, 0.36, 1] }}
          className="w-full max-w-sm"
        >
          <div className="mb-10 text-center">
            <motion.div
              initial={{ opacity: 0, scale: 0.8, rotateZ: -10 }}
              animate={{ opacity: 1, scale: 1, rotateZ: 0 }}
              transition={{ duration: 0.6, ease: [0.34, 1.56, 0.64, 1] }}
              className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-accent/40 to-accent/20 border border-accent/30 relative"
            >
              <motion.div
                className="absolute inset-0 rounded-2xl bg-accent/10"
                animate={{ scale: [1, 1.2, 1] }}
                transition={{ duration: 3, repeat: Infinity }}
              />
              <span className="text-2xl font-bold tracking-tight text-accent relative z-10">S</span>
            </motion.div>
            <motion.h1
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5, delay: 0.1 }}
              className="text-3xl font-bold tracking-tight text-foreground"
            >
              Stokr
            </motion.h1>
            <motion.p
              initial={{ opacity: 0, y: 4 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.5, delay: 0.2 }}
              className="mt-2 text-sm text-muted-foreground"
            >
              Institutional-grade execution console
            </motion.p>
          </div>

          <motion.div
            variants={containerVariants}
            initial="hidden"
            animate="show"
            className="rounded-2xl border border-border/50 bg-card/60 backdrop-blur-xl p-8 shadow-2xl"
          >
            <form className="space-y-5" onSubmit={onSubmit}>
              <motion.div variants={itemVariants}>
                <label className="block text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">
                  Email or username
                </label>
                <div className="relative group">
                  <Mail className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-muted-foreground/50 transition-colors group-focus-within:text-accent/70" />
                  <input
                    className="w-full rounded-xl border border-border bg-background/40 backdrop-blur-sm py-3 pl-12 pr-4 text-sm text-foreground outline-none ring-accent/30 placeholder:text-muted-foreground/40 focus:border-accent/60 focus:ring-2 focus:bg-background/50 transition-all duration-300"
                    placeholder="you@company.com"
                    value={principal}
                    onChange={(e) => setPrincipal(e.target.value)}
                    autoComplete="username"
                  />
                </div>
              </motion.div>

              <motion.div variants={itemVariants}>
                <label className="block text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">
                  Password
                </label>
                <div className="relative group">
                  <Lock className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-muted-foreground/50 transition-colors group-focus-within:text-accent/70" />
                  <input
                    className="w-full rounded-xl border border-border bg-background/40 backdrop-blur-sm py-3 pl-12 pr-4 text-sm text-foreground outline-none ring-accent/30 placeholder:text-muted-foreground/40 focus:border-accent/60 focus:ring-2 focus:bg-background/50 transition-all duration-300"
                    type="password"
                    placeholder="Enter your password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    autoComplete="current-password"
                  />
                </div>
              </motion.div>

              <motion.div variants={itemVariants} className="flex justify-end pt-1">
                <Link
                  className="text-xs font-semibold text-accent/70 hover:text-accent transition-all duration-200 hover:gap-1 flex items-center"
                  to="/forgot-password"
                >
                  Forgot password?
                  <ArrowRight className="h-3 w-3 opacity-0 group-hover:opacity-100 transition-opacity" />
                </Link>
              </motion.div>

              {error ? (
                <motion.div
                  initial={{ opacity: 0, y: -8, scale: 0.95 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  className="rounded-lg bg-gradient-to-r from-risk-high/20 to-risk-high/10 border border-risk-high/30 px-4 py-3 text-sm text-risk-high/90 backdrop-blur-sm"
                >
                  <div className="flex gap-2">
                    <span className="text-lg">⚠️</span>
                    <span>{error}</span>
                  </div>
                </motion.div>
              ) : null}

              <motion.button
                variants={itemVariants}
                whileHover={{ scale: 1.02, boxShadow: "0 12px 32px oklch(0.62 0.19 258 / 0.3)" }}
                whileTap={{ scale: 0.98 }}
                disabled={loading}
                type="submit"
                className={cn(
                  "w-full flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-accent to-accent/80 py-3 text-sm font-bold text-background shadow-lg transition-all duration-300 hover:shadow-xl disabled:opacity-60 disabled:cursor-not-allowed",
                  loading && "opacity-70",
                )}
              >
                {loading ? (
                  <>
                    <motion.div
                      animate={{ rotate: 360 }}
                      transition={{ duration: 1.5, repeat: Infinity }}
                      className="inline-block"
                    >
                      <span className="text-lg">⟳</span>
                    </motion.div>
                    Signing in...
                  </>
                ) : (
                  <>
                    Sign in
                    <motion.div
                      animate={{ x: [0, 4, 0] }}
                      transition={{ duration: 1.5, repeat: Infinity }}
                    >
                      <ArrowRight className="h-5 w-5" />
                    </motion.div>
                  </>
                )}
              </motion.button>

              <motion.p variants={itemVariants} className="text-center text-sm text-muted-foreground pt-2">
                New here?{" "}
                <Link className="font-semibold text-accent/80 hover:text-accent transition-colors duration-200" to="/register">
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
