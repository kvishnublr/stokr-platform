import { FormEvent, useMemo, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { motion } from "framer-motion";
import { UserPlus } from "lucide-react";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";
import { useSessionStore } from "../state/session";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function RegisterPage() {
  const token = useSessionStore((s) => s.accessToken);
  const navigate = useNavigate();

  const [displayName, setDisplayName] = useState("");
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [mobilePhone, setMobilePhone] = useState("");
  const [telegramUsername, setTelegramUsername] = useState("");
  const [whatsAppNumber, setWhatsAppNumber] = useState("");
  const [loading, setLoading] = useState(false);

  const inlineErrors = useMemo(() => {
    const e: Record<string, string> = {};
    if (email && !EMAIL_RE.test(email)) e.email = "Enter a valid email address.";
    if (username && !/^[a-zA-Z][a-zA-Z0-9_]{2,31}$/.test(username)) {
      e.username = "3–32 characters; letters, digits, underscore; start with a letter.";
    }
    if (password && password.length < 12) e.password = "At least 12 characters.";
    if (confirm && confirm !== password) e.confirm = "Passwords must match.";
    return e;
  }, [email, username, password, confirm]);

  if (token) {
    return <Navigate to="/" replace />;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (Object.keys(inlineErrors).length > 0) return;
    setLoading(true);
    try {
      await api.post("/api/auth/register", {
        displayName,
        username,
        email,
        password,
        confirmPassword: confirm,
        ...(mobilePhone.trim() ? { mobilePhone: mobilePhone.trim() } : {}),
        ...(telegramUsername.trim() ? { telegramUsername: telegramUsername.trim().replace(/^@/, "") } : {}),
        ...(whatsAppNumber.trim() ? { whatsAppNumber: whatsAppNumber.trim() } : {}),
      });
      toast.success("Account created — sign in with your credentials.");
      navigate("/login", { replace: true });
    } catch (err: unknown) {
      toast.error(parseAxiosMessage(err));
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
          className="w-full max-w-md"
        >
          <div className="mb-8 text-center">
            <div className="text-3xl font-semibold tracking-tight text-white">Join Stokr</div>
            <p className="mt-2 text-sm text-neutral-400">
              Self-service signup · assigned <span className="font-mono text-neutral-300">ROLE_TRADER</span>
            </p>
          </div>

          <div className="rounded-2xl border border-neutral-800 bg-neutral-950/80 p-8 shadow-2xl backdrop-blur">
            <form className="space-y-4" onSubmit={onSubmit}>
              <div>
                <label className="text-xs font-medium uppercase tracking-wide text-neutral-500">Full name</label>
                <input
                  required
                  className="mt-2 w-full rounded-lg border border-neutral-800 bg-neutral-900/80 px-3 py-2 text-sm text-white outline-none focus:border-blue-500/50 focus:ring-2 focus:ring-blue-500/30"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  autoComplete="name"
                />
              </div>
              <div>
                <label className="text-xs font-medium uppercase tracking-wide text-neutral-500">Username</label>
                <input
                  required
                  className={cn(
                    "mt-2 w-full rounded-lg border bg-neutral-900/80 px-3 py-2 text-sm text-white outline-none focus:ring-2",
                    inlineErrors.username ? "border-red-800 focus:border-red-600 focus:ring-red-500/30" : "border-neutral-800 focus:border-blue-500/50 focus:ring-blue-500/30",
                  )}
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoComplete="username"
                />
                {inlineErrors.username ? (
                  <p className="mt-1 text-xs text-red-400">{inlineErrors.username}</p>
                ) : null}
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label className="text-xs font-medium uppercase tracking-wide text-neutral-500">
                    Mobile (optional)
                  </label>
                  <input
                    className="mt-2 w-full rounded-lg border border-neutral-800 bg-neutral-900/80 px-3 py-2 text-sm text-white outline-none focus:border-blue-500/50 focus:ring-2 focus:ring-blue-500/30"
                    value={mobilePhone}
                    onChange={(e) => setMobilePhone(e.target.value)}
                    autoComplete="tel"
                    placeholder="+1…"
                  />
                </div>
                <div>
                  <label className="text-xs font-medium uppercase tracking-wide text-neutral-500">
                    Telegram @username (optional)
                  </label>
                  <input
                    className="mt-2 w-full rounded-lg border border-neutral-800 bg-neutral-900/80 px-3 py-2 text-sm text-white outline-none focus:border-blue-500/50 focus:ring-2 focus:ring-blue-500/30"
                    value={telegramUsername}
                    onChange={(e) => setTelegramUsername(e.target.value)}
                    placeholder="without @"
                  />
                </div>
                <div className="sm:col-span-2">
                  <label className="text-xs font-medium uppercase tracking-wide text-neutral-500">
                    WhatsApp number (optional)
                  </label>
                  <input
                    className="mt-2 w-full rounded-lg border border-neutral-800 bg-neutral-900/80 px-3 py-2 text-sm text-white outline-none focus:border-blue-500/50 focus:ring-2 focus:ring-blue-500/30"
                    value={whatsAppNumber}
                    onChange={(e) => setWhatsAppNumber(e.target.value)}
                    placeholder="E.164 e.g. +9198…"
                  />
                </div>
              </div>
              <div>
                <label className="text-xs font-medium uppercase tracking-wide text-neutral-500">Email</label>
                <input
                  required
                  type="email"
                  className={cn(
                    "mt-2 w-full rounded-lg border bg-neutral-900/80 px-3 py-2 text-sm text-white outline-none focus:ring-2",
                    inlineErrors.email ? "border-red-800 focus:border-red-600" : "border-neutral-800 focus:border-blue-500/50 focus:ring-blue-500/30",
                  )}
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  autoComplete="email"
                />
                {inlineErrors.email ? <p className="mt-1 text-xs text-red-400">{inlineErrors.email}</p> : null}
              </div>
              <div>
                <label className="text-xs font-medium uppercase tracking-wide text-neutral-500">Password</label>
                <input
                  required
                  type="password"
                  className={cn(
                    "mt-2 w-full rounded-lg border bg-neutral-900/80 px-3 py-2 text-sm text-white outline-none focus:ring-2",
                    inlineErrors.password ? "border-red-800" : "border-neutral-800 focus:border-blue-500/50 focus:ring-blue-500/30",
                  )}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="new-password"
                />
                <p className="mt-1 text-xs text-neutral-500">
                  Min 12 chars with upper, lower, number, and symbol.
                </p>
                {inlineErrors.password ? (
                  <p className="mt-1 text-xs text-red-400">{inlineErrors.password}</p>
                ) : null}
              </div>
              <div>
                <label className="text-xs font-medium uppercase tracking-wide text-neutral-500">
                  Confirm password
                </label>
                <input
                  required
                  type="password"
                  className={cn(
                    "mt-2 w-full rounded-lg border bg-neutral-900/80 px-3 py-2 text-sm text-white outline-none focus:ring-2",
                    inlineErrors.confirm ? "border-red-800" : "border-neutral-800 focus:border-blue-500/50 focus:ring-blue-500/30",
                  )}
                  value={confirm}
                  onChange={(e) => setConfirm(e.target.value)}
                  autoComplete="new-password"
                />
                {inlineErrors.confirm ? (
                  <p className="mt-1 text-xs text-red-400">{inlineErrors.confirm}</p>
                ) : null}
              </div>

              <button
                disabled={loading || Object.keys(inlineErrors).length > 0}
                type="submit"
                className="flex w-full items-center justify-center gap-2 rounded-lg bg-white py-2.5 text-sm font-semibold text-neutral-950 hover:bg-neutral-200 disabled:opacity-50"
              >
                <UserPlus className="h-4 w-4" />
                {loading ? "Creating…" : "Create account"}
              </button>

              <p className="text-center text-sm text-neutral-500">
                Already have access?{" "}
                <Link className="font-medium text-blue-400 hover:text-blue-300" to="/login">
                  Sign in
                </Link>
              </p>
            </form>
          </div>
        </motion.div>
      </div>
    </div>
  );
}
