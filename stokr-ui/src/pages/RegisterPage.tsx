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
    <div className="relative min-h-screen overflow-hidden bg-gradient-to-br from-slate-50 via-white to-blue-50">
      {/* Animated background shapes */}
      <div className="pointer-events-none absolute inset-0">
        <motion.div
          animate={{
            x: [0, 150, 0],
            y: [0, 80, 0],
          }}
          transition={{ duration: 25, repeat: Infinity, ease: "easeInOut" }}
          className="absolute -top-96 -right-96 h-96 w-96 rounded-full bg-gradient-to-br from-blue-300/40 to-purple-300/40 blur-3xl"
        />
        <motion.div
          animate={{
            x: [0, -150, 0],
            y: [0, -80, 0],
          }}
          transition={{ duration: 30, repeat: Infinity, ease: "easeInOut" }}
          className="absolute -bottom-96 -left-96 h-96 w-96 rounded-full bg-gradient-to-br from-emerald-300/40 to-cyan-300/40 blur-3xl"
        />
        <motion.div
          animate={{
            scale: [1, 1.1, 1],
            opacity: [0.3, 0.5, 0.3],
          }}
          transition={{ duration: 20, repeat: Infinity }}
          className="absolute top-1/3 right-1/4 h-64 w-64 rounded-full bg-gradient-to-br from-pink-200/30 to-rose-200/30 blur-3xl"
        />
      </div>

      <div className="relative flex min-h-screen items-center justify-center px-4 py-8 sm:py-16">
        <motion.div
          initial={{ opacity: 0, y: 30 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.7 }}
          className="w-full max-w-md"
        >
          {/* Header */}
          <motion.div
            initial={{ opacity: 0, y: -30 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.7, delay: 0.1 }}
            className="mb-10 text-center"
          >
            <motion.div
              animate={{ scale: [1, 1.1, 1], rotate: [0, 10, -10, 0] }}
              transition={{ duration: 4, repeat: Infinity }}
              className="mb-4 inline-block"
            >
              <div className="inline-flex h-16 w-16 items-center justify-center rounded-full bg-gradient-to-br from-blue-500 to-purple-500 shadow-lg">
                <CheckCircle2 className="h-8 w-8 text-white" />
              </div>
            </motion.div>
            <h1 className="text-5xl font-bold tracking-tight text-gray-900">
              Join <span className="bg-gradient-to-r from-blue-600 via-purple-600 to-pink-600 bg-clip-text text-transparent">Stokr</span>
            </h1>
            <p className="mt-4 text-base text-gray-600">
              Begin your professional trading journey with <span className="font-semibold text-blue-600">Premium Access</span>
            </p>
          </motion.div>

          {/* Form Card */}
          <motion.div
            initial={{ opacity: 0, y: 30, scale: 0.9 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            transition={{ duration: 0.7, delay: 0.2 }}
            className="relative overflow-hidden rounded-3xl border border-white/80 bg-white/95 p-8 shadow-2xl backdrop-blur-xl"
          >
            {/* Card gradient overlay */}
            <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-blue-50/50 via-transparent to-transparent" />

            <form className="relative z-10 space-y-5" onSubmit={onSubmit}>
              <motion.div
                initial={{ opacity: 0, x: -20 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.5, delay: 0.3 }}
                className="space-y-2"
              >
                <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-gray-700">
                  <User className="h-4 w-4 text-blue-600" />
                  Full Name
                </label>
                <input
                  required
                  className="w-full rounded-2xl border-2 border-gray-200 bg-gray-50/50 px-4 py-3 text-sm text-gray-900 outline-none transition-all duration-300 placeholder:text-gray-400 focus:border-blue-500 focus:bg-white focus:shadow-md focus:ring-2 focus:ring-blue-200"
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
                <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-gray-700">
                  <User className="h-4 w-4 text-purple-600" />
                  Username
                </label>
                <input
                  required
                  className={cn(
                    "w-full rounded-2xl border-2 bg-gray-50/50 px-4 py-3 text-sm text-gray-900 outline-none transition-all duration-300 placeholder:text-gray-400 focus:bg-white focus:shadow-md focus:ring-2",
                    inlineErrors.username
                      ? "border-red-300 focus:border-red-500 focus:ring-red-200"
                      : "border-gray-200 focus:border-purple-500 focus:ring-purple-200",
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
                    className="text-xs font-medium text-red-600"
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
                  <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-gray-700">
                    <Smartphone className="h-4 w-4 text-blue-600" />
                    Mobile
                  </label>
                  <input
                    className="w-full rounded-2xl border-2 border-gray-200 bg-gray-50/50 px-4 py-3 text-sm text-gray-900 outline-none transition-all duration-300 placeholder:text-gray-400 focus:border-blue-500 focus:bg-white focus:shadow-md focus:ring-2 focus:ring-blue-200"
                    value={mobilePhone}
                    onChange={(e) => setMobilePhone(e.target.value)}
                    autoComplete="tel"
                    placeholder="+1..."
                  />
                </div>
                <div className="space-y-2">
                  <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-gray-700">
                    <Send className="h-4 w-4 text-purple-600" />
                    Telegram
                  </label>
                  <input
                    className="w-full rounded-2xl border-2 border-gray-200 bg-gray-50/50 px-4 py-3 text-sm text-gray-900 outline-none transition-all duration-300 placeholder:text-gray-400 focus:border-purple-500 focus:bg-white focus:shadow-md focus:ring-2 focus:ring-purple-200"
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
                <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-gray-700">
                  <Send className="h-4 w-4 text-emerald-600" />
                  WhatsApp
                </label>
                <input
                  className="w-full rounded-2xl border-2 border-gray-200 bg-gray-50/50 px-4 py-3 text-sm text-gray-900 outline-none transition-all duration-300 placeholder:text-gray-400 focus:border-emerald-500 focus:bg-white focus:shadow-md focus:ring-2 focus:ring-emerald-200"
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
                <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-gray-700">
                  <Mail className="h-4 w-4 text-blue-600" />
                  Email
                </label>
                <input
                  required
                  type="email"
                  className={cn(
                    "w-full rounded-2xl border-2 bg-gray-50/50 px-4 py-3 text-sm text-gray-900 outline-none transition-all duration-300 placeholder:text-gray-400 focus:bg-white focus:shadow-md focus:ring-2",
                    inlineErrors.email
                      ? "border-red-300 focus:border-red-500 focus:ring-red-200"
                      : "border-gray-200 focus:border-blue-500 focus:ring-blue-200",
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
                    className="text-xs font-medium text-red-600"
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
                <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-gray-700">
                  <Lock className="h-4 w-4 text-purple-600" />
                  Password
                </label>
                <input
                  required
                  type="password"
                  className={cn(
                    "w-full rounded-2xl border-2 bg-gray-50/50 px-4 py-3 text-sm text-gray-900 outline-none transition-all duration-300 placeholder:text-gray-400 focus:bg-white focus:shadow-md focus:ring-2",
                    inlineErrors.password
                      ? "border-red-300 focus:border-red-500 focus:ring-red-200"
                      : "border-gray-200 focus:border-purple-500 focus:ring-purple-200",
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
                      <span className="text-xs font-semibold text-gray-600">Password Strength</span>
                      <span className={`text-xs font-bold ${passwordStrength.color.includes("red") ? "text-red-600" : passwordStrength.color.includes("amber") ? "text-amber-600" : passwordStrength.color.includes("yellow") ? "text-yellow-600" : "text-emerald-600"}`}>
                        {passwordStrength.label}
                      </span>
                    </div>
                    <div className="h-2.5 overflow-hidden rounded-full bg-gray-200">
                      <motion.div
                        initial={{ width: 0 }}
                        animate={{ width: `${(passwordStrength.score / 5) * 100}%` }}
                        transition={{ duration: 0.5 }}
                        className={`h-full bg-gradient-to-r ${passwordStrength.color} shadow-lg`}
                      />
                    </div>
                  </motion.div>
                )}

                {inlineErrors.password && (
                  <motion.p
                    initial={{ opacity: 0, y: -10 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="text-xs font-medium text-red-600"
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
                <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-widest text-gray-700">
                  <Lock className="h-4 w-4 text-emerald-600" />
                  Confirm Password
                </label>
                <input
                  required
                  type="password"
                  className={cn(
                    "w-full rounded-2xl border-2 bg-gray-50/50 px-4 py-3 text-sm text-gray-900 outline-none transition-all duration-300 placeholder:text-gray-400 focus:bg-white focus:shadow-md focus:ring-2",
                    inlineErrors.confirm
                      ? "border-red-300 focus:border-red-500 focus:ring-red-200"
                      : "border-gray-200 focus:border-emerald-500 focus:ring-emerald-200",
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
                    className="text-xs font-medium text-red-600"
                  >
                    {inlineErrors.confirm}
                  </motion.p>
                )}
              </motion.div>

              <motion.button
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.5, delay: 0.65 }}
                whileHover={{ scale: 1.02, boxShadow: "0 20px 40px rgba(59, 130, 246, 0.3)" }}
                whileTap={{ scale: 0.98 }}
                disabled={loading || Object.keys(inlineErrors).length > 0}
                type="submit"
                className="group relative w-full overflow-hidden rounded-2xl bg-gradient-to-r from-blue-600 via-purple-600 to-pink-600 py-3 font-bold text-white shadow-xl transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <div className="absolute inset-0 bg-gradient-to-r from-blue-700 via-purple-700 to-pink-700 opacity-0 transition-opacity duration-300 group-hover:opacity-100" />
                <div className="relative flex items-center justify-center gap-2">
                  {loading ? (
                    <>
                      <motion.div
                        animate={{ rotate: 360 }}
                        transition={{ duration: 1, repeat: Infinity }}
                        className="h-5 w-5 border-2 border-white/40 border-t-white rounded-full"
                      />
                      Creating Account...
                    </>
                  ) : (
                    <>
                      <UserPlus className="h-5 w-5 transition-transform duration-300 group-hover:scale-125" />
                      Create Account
                    </>
                  )}
                </div>
              </motion.button>

              <motion.div
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.5, delay: 0.7 }}
                className="pt-2 text-center"
              >
                <p className="text-sm text-gray-600">
                  Already have an account?{" "}
                  <Link
                    className="font-bold text-transparent bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text transition-all duration-300 hover:from-purple-600 hover:to-pink-600"
                    to="/login"
                  >
                    Sign in
                  </Link>
                </p>
              </motion.div>
            </form>
          </motion.div>

          {/* Footer note */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.5, delay: 0.75 }}
            className="mt-8 flex items-center justify-center gap-2 text-center"
          >
            <div className="flex items-center gap-2 text-sm text-gray-600">
              <div className="h-1 w-1 rounded-full bg-gradient-to-r from-blue-500 to-purple-500" />
              <span>Bank-grade security & SSL encryption</span>
              <div className="h-1 w-1 rounded-full bg-gradient-to-r from-purple-500 to-pink-500" />
            </div>
          </motion.div>
        </motion.div>
      </div>
    </div>
  );
}
