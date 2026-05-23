import { FormEvent, useMemo, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { motion } from "framer-motion";
import { UserPlus, CheckCircle2, Lock, Mail, User, Smartphone, Send } from "lucide-react";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";
import { useSessionStore } from "../state/session";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// Password strength checker
function getPasswordStrength(pwd: string): { score: number; label: string; color: string } {
  let score = 0;
  if (pwd.length >= 12) score++;
  if (pwd.length >= 16) score++;
  if (/[A-Z]/.test(pwd) && /[a-z]/.test(pwd)) score++;
  if (/\d/.test(pwd)) score++;
  if (/[^A-Za-z0-9]/.test(pwd)) score++;

  if (score <= 2) return { score, label: "Weak", color: "from-red-500 to-red-600" };
  if (score <= 3) return { score, label: "Fair", color: "from-amber-500 to-amber-600" };
  if (score <= 4) return { score, label: "Good", color: "from-yellow-500 to-yellow-600" };
  return { score, label: "Strong", color: "from-emerald-500 to-emerald-600" };
}

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: {
      staggerChildren: 0.08,
      delayChildren: 0.2,
    },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 20 },
  visible: {
    opacity: 1,
    y: 0,
    transition: {
      type: "spring",
      stiffness: 100,
      damping: 15,
    },
  },
};

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
      e.username = "3-32 characters; letters, digits, underscore; start with a letter.";
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
      const reg = await api.post<{ data?: { verificationEmailStatus?: string } }>("/api/auth/register", {
        displayName,
        username,
        email,
        password,
        confirmPassword: confirm,
        ...(mobilePhone.trim() ? { mobilePhone: mobilePhone.trim() } : {}),
        ...(telegramUsername.trim() ? { telegramUsername: telegramUsername.trim().replace(/^@/, "") } : {}),
        ...(whatsAppNumber.trim() ? { whatsAppNumber: whatsAppNumber.trim() } : {}),
      });
      const vStatus = reg.data?.data?.verificationEmailStatus;
      if (vStatus === "SENT") {
        toast.success("Account created - check your inbox to verify your email, then sign in.");
      } else if (vStatus === "NOT_CONFIGURED") {
        toast.message("SMTP not configured. Use verification URL from logs.", { duration: 12_000 });
      } else if (vStatus === "SEND_FAILED") {
        toast.error("Account created, but the verification email could not be sent. Configure SMTP or use resend after fixing mail.");
      } else {
        toast.success("Account created - sign in with your credentials.");
      }
      navigate("/login", { replace: true });
    } catch (err: unknown) {
      toast.error(parseAxiosMessage(err));
    } finally {
      setLoading(false);
    }
  }

  const passwordStrength = getPasswordStrength(password);

  return (
    <div className="relative min-h-screen overflow-hidden bg-gradient-to-br from-neutral-950 via-neutral-950 to-neutral-900">
      {/* Animated gradient orbs background */}
      <div className="pointer-events-none absolute inset-0">
        <motion.div
          animate={{
            x: [0, 100, 0],
            y: [0, 50, 0],
          }}
          transition={{ duration: 20, repeat: Infinity }}
          className="absolute -top-40 -left-40 h-80 w-80 rounded-full bg-gradient-to-br from-blue-500/20 to-purple-500/20 blur-3xl"
        />
        <motion.div
          animate={{
            x: [0, -100, 0],
            y: [0, -50, 0],
          }}
          transition={{ duration: 25, repeat: Infinity }}
          className="absolute -bottom-40 -right-40 h-80 w-80 rounded-full bg-gradient-to-br from-emerald-500/20 to-blue-500/20 blur-3xl"
        />
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_top,_rgba(59,130,246,0.1),transparent_50%)]" />
      </div>

      <div className="relative flex min-h-screen items-center justify-center px-4 py-8 sm:py-16">
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
          className="w-full max-w-md"
        >
          {/* Header */}
          <motion.div
            initial={{ opacity: 0, y: -20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.1 }}
            className="mb-8 text-center"
          >
            <motion.div
              animate={{ scale: [1, 1.05, 1] }}
              transition={{ duration: 3, repeat: Infinity }}
              className="mb-4 inline-block"
            >
              <CheckCircle2 className="h-12 w-12 text-emerald-400" />
            </motion.div>
            <h1 className="text-4xl font-bold tracking-tighter text-white">
              Join <span className="bg-gradient-to-r from-blue-400 via-purple-400 to-emerald-400 bg-clip-text text-transparent">Stokr</span>
            </h1>
            <p className="mt-3 text-sm text-neutral-400">
              Start your trading journey · Premium <span className="font-semibold text-blue-300">TRADER</span> access
            </p>
          </motion.div>

          {/* Form Card */}
          <motion.div
            initial={{ opacity: 0, y: 20, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            transition={{ duration: 0.6, delay: 0.2 }}
            className="relative overflow-hidden rounded-2xl border border-neutral-800/50 bg-neutral-950/60 p-8 shadow-2xl backdrop-blur-xl"
          >
            {/* Card shine effect */}
            <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-white/5 via-transparent to-transparent" />

            <form className="relative z-10 space-y-5" onSubmit={onSubmit}>
              <motion.div
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.5, delay: 0.3 }}
                className="space-y-2"
              >
                <label className="flex items-center gap-2 text-xs font-semibold uppercase tracking-widest text-neutral-400">
                  <User className="h-3 w-3 text-blue-400" />
                  Full Name
                </label>
                <input
                  required
                  className="w-full rounded-xl border border-neutral-700/50 bg-neutral-900/40 px-4 py-3 text-sm text-white outline-none transition-all duration-300 placeholder:text-neutral-600 focus:border-blue-500/60 focus:bg-neutral-900/60 focus:ring-2 focus:ring-blue-500/20"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  autoComplete="name"
                  placeholder="Your full name"
                />
              </motion.div>

              <motion.div
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.5, delay: 0.35 }}
                className="space-y-2"
              >
                <label className="flex items-center gap-2 text-xs font-semibold uppercase tracking-widest text-neutral-400">
                  <User className="h-3 w-3 text-purple-400" />
                  Username
                </label>
                <input
                  required
                  className={cn(
                    "w-full rounded-xl border bg-neutral-900/40 px-4 py-3 text-sm text-white outline-none transition-all duration-300 placeholder:text-neutral-600 focus:bg-neutral-900/60 focus:ring-2",
                    inlineErrors.username
                      ? "border-red-700/50 focus:border-red-500 focus:ring-red-500/20"
                      : "border-neutral-700/50 focus:border-purple-500/60 focus:ring-purple-500/20",
                  )}
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoComplete="username"
                  placeholder="3-32 characters"
                />
                {inlineErrors.username && (
                  <motion.p
                    initial={{ opacity: 0, y: -10 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="text-xs text-red-400"
                  >
                    {inlineErrors.username}
                  </motion.p>
                )}
              </motion.div>

              <motion.div
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.5, delay: 0.4 }}
                className="grid gap-4 sm:grid-cols-2"
              >
                <div className="space-y-2">
                  <label className="flex items-center gap-2 text-xs font-semibold uppercase tracking-widest text-neutral-400">
                    <Smartphone className="h-3 w-3 text-blue-400" />
                    Mobile
                  </label>
                  <input
                    className="w-full rounded-xl border border-neutral-700/50 bg-neutral-900/40 px-4 py-3 text-sm text-white outline-none transition-all duration-300 placeholder:text-neutral-600 focus:border-blue-500/60 focus:bg-neutral-900/60 focus:ring-2 focus:ring-blue-500/20"
                    value={mobilePhone}
                    onChange={(e) => setMobilePhone(e.target.value)}
                    autoComplete="tel"
                    placeholder="+1..."
                  />
                </div>
                <div className="space-y-2">
                  <label className="flex items-center gap-2 text-xs font-semibold uppercase tracking-widest text-neutral-400">
                    <Send className="h-3 w-3 text-purple-400" />
                    Telegram
                  </label>
                  <input
                    className="w-full rounded-xl border border-neutral-700/50 bg-neutral-900/40 px-4 py-3 text-sm text-white outline-none transition-all duration-300 placeholder:text-neutral-600 focus:border-purple-500/60 focus:bg-neutral-900/60 focus:ring-2 focus:ring-purple-500/20"
                    value={telegramUsername}
                    onChange={(e) => setTelegramUsername(e.target.value)}
                    placeholder="username"
                  />
                </div>
              </motion.div>

              <motion.div
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.5, delay: 0.45 }}
                className="space-y-2"
              >
                <label className="flex items-center gap-2 text-xs font-semibold uppercase tracking-widest text-neutral-400">
                  <Send className="h-3 w-3 text-emerald-400" />
                  WhatsApp
                </label>
                <input
                  className="w-full rounded-xl border border-neutral-700/50 bg-neutral-900/40 px-4 py-3 text-sm text-white outline-none transition-all duration-300 placeholder:text-neutral-600 focus:border-emerald-500/60 focus:bg-neutral-900/60 focus:ring-2 focus:ring-emerald-500/20"
                  value={whatsAppNumber}
                  onChange={(e) => setWhatsAppNumber(e.target.value)}
                  placeholder="+9198..."
                />
              </motion.div>

              <motion.div
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.5, delay: 0.5 }}
                className="space-y-2"
              >
                <label className="flex items-center gap-2 text-xs font-semibold uppercase tracking-widest text-neutral-400">
                  <Mail className="h-3 w-3 text-blue-400" />
                  Email
                </label>
                <input
                  required
                  type="email"
                  className={cn(
                    "w-full rounded-xl border bg-neutral-900/40 px-4 py-3 text-sm text-white outline-none transition-all duration-300 placeholder:text-neutral-600 focus:bg-neutral-900/60 focus:ring-2",
                    inlineErrors.email
                      ? "border-red-700/50 focus:border-red-500 focus:ring-red-500/20"
                      : "border-neutral-700/50 focus:border-blue-500/60 focus:ring-blue-500/20",
                  )}
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  autoComplete="email"
                  placeholder="you@example.com"
                />
                {inlineErrors.email && (
                  <motion.p
                    initial={{ opacity: 0, y: -10 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="text-xs text-red-400"
                  >
                    {inlineErrors.email}
                  </motion.p>
                )}
              </motion.div>

              <motion.div
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.5, delay: 0.55 }}
                className="space-y-3"
              >
                <label className="flex items-center gap-2 text-xs font-semibold uppercase tracking-widest text-neutral-400">
                  <Lock className="h-3 w-3 text-purple-400" />
                  Password
                </label>
                <input
                  required
                  type="password"
                  className={cn(
                    "w-full rounded-xl border bg-neutral-900/40 px-4 py-3 text-sm text-white outline-none transition-all duration-300 placeholder:text-neutral-600 focus:bg-neutral-900/60 focus:ring-2",
                    inlineErrors.password
                      ? "border-red-700/50 focus:border-red-500 focus:ring-red-500/20"
                      : "border-neutral-700/50 focus:border-purple-500/60 focus:ring-purple-500/20",
                  )}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="new-password"
                  placeholder="Min 12 characters"
                />

                {password && (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: "auto" }}
                    transition={{ duration: 0.3 }}
                    className="space-y-2"
                  >
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-neutral-400">Strength</span>
                      <span className={`text-xs font-semibold ${passwordStrength.color.includes("red") ? "text-red-400" : passwordStrength.color.includes("amber") ? "text-amber-400" : passwordStrength.color.includes("yellow") ? "text-yellow-400" : "text-emerald-400"}`}>
                        {passwordStrength.label}
                      </span>
                    </div>
                    <div className="h-2 overflow-hidden rounded-full bg-neutral-800">
                      <motion.div
                        initial={{ width: 0 }}
                        animate={{ width: `${(passwordStrength.score / 5) * 100}%` }}
                        transition={{ duration: 0.5 }}
                        className={`h-full bg-gradient-to-r ${passwordStrength.color}`}
                      />
                    </div>
                  </motion.div>
                )}

                {inlineErrors.password && (
                  <motion.p
                    initial={{ opacity: 0, y: -10 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="text-xs text-red-400"
                  >
                    {inlineErrors.password}
                  </motion.p>
                )}
              </motion.div>

              <motion.div
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.5, delay: 0.6 }}
                className="space-y-2"
              >
                <label className="flex items-center gap-2 text-xs font-semibold uppercase tracking-widest text-neutral-400">
                  <Lock className="h-3 w-3 text-emerald-400" />
                  Confirm Password
                </label>
                <input
                  required
                  type="password"
                  className={cn(
                    "w-full rounded-xl border bg-neutral-900/40 px-4 py-3 text-sm text-white outline-none transition-all duration-300 placeholder:text-neutral-600 focus:bg-neutral-900/60 focus:ring-2",
                    inlineErrors.confirm
                      ? "border-red-700/50 focus:border-red-500 focus:ring-red-500/20"
                      : "border-neutral-700/50 focus:border-emerald-500/60 focus:ring-emerald-500/20",
                  )}
                  value={confirm}
                  onChange={(e) => setConfirm(e.target.value)}
                  autoComplete="new-password"
                  placeholder="Confirm password"
                />
                {inlineErrors.confirm && (
                  <motion.p
                    initial={{ opacity: 0, y: -10 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="text-xs text-red-400"
                  >
                    {inlineErrors.confirm}
                  </motion.p>
                )}
              </motion.div>

              <motion.button
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.5, delay: 0.65 }}
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
                disabled={loading || Object.keys(inlineErrors).length > 0}
                type="submit"
                className="group relative w-full overflow-hidden rounded-xl bg-gradient-to-r from-blue-600 via-purple-600 to-emerald-600 py-3 font-semibold text-white shadow-lg transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <div className="absolute inset-0 bg-gradient-to-r from-blue-500 via-purple-500 to-emerald-500 opacity-0 transition-opacity duration-300 group-hover:opacity-100" />
                <div className="relative flex items-center justify-center gap-2">
                  {loading ? (
                    <>
                      <motion.div
                        animate={{ rotate: 360 }}
                        transition={{ duration: 1, repeat: Infinity }}
                        className="h-4 w-4 border-2 border-white/30 border-t-white rounded-full"
                      />
                      Creating Account...
                    </>
                  ) : (
                    <>
                      <UserPlus className="h-4 w-4 transition-transform duration-300 group-hover:scale-110" />
                      Create Account
                    </>
                  )}
                </div>
              </motion.button>

              <motion.p
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                transition={{ duration: 0.5, delay: 0.7 }}
                className="text-center text-sm text-neutral-400"
              >
                Already have access?{" "}
                <Link
                  className="font-semibold text-transparent bg-gradient-to-r from-blue-400 to-purple-400 bg-clip-text transition-all duration-300 hover:from-purple-400 hover:to-emerald-400"
                  to="/login"
                >
                  Sign in
                </Link>
              </motion.p>
            </form>
          </motion.div>

          {/* Footer note */}
          <motion.p
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.5, delay: 0.75 }}
            className="mt-6 text-center text-xs text-neutral-500"
          >
            🔒 Your data is secure and encrypted · Privacy-first trading
          </motion.p>
        </motion.div>
      </div>
    </div>
  );
}
