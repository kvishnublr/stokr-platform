import { FormEvent, useState } from "react";
import { Link, Navigate } from "react-router-dom";
import { motion } from "framer-motion";
import { Lock, Mail, Zap } from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import type { AuthPayload } from "../state/session";
import { useSessionStore } from "../state/session";

export function SplitLayoutLoginPage() {
  const token = useSessionStore((s) => s.accessToken);
  const setSession = useSessionStore((s) => s.setSession);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  if (token) return <Navigate to="/" replace />;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const res = await api.post("/api/auth/login", { email, password });
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
        whatsAppVerified: Boolean(d.whatsAppVerified ?? (d as { whatsappVerified?: boolean }).whatsappVerified),
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
    <div className="min-h-screen grid grid-cols-1 lg:grid-cols-2">
      {/* Left Section - Branding */}
      <motion.div
        initial={{ opacity: 0, x: -50 }}
        animate={{ opacity: 1, x: 0 }}
        transition={{ duration: 0.8 }}
        className="hidden lg:flex flex-col justify-center items-start px-12 py-20 bg-gradient-to-br from-blue-50 via-white to-emerald-50"
      >
        <div className="mb-12">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-blue-500 rounded-lg flex items-center justify-center">
              <span className="text-white font-bold text-lg">S</span>
            </div>
            <div>
              <h2 className="font-bold text-lg text-gray-900">Stokr</h2>
              <p className="text-xs text-gray-500">AURORA PRO</p>
            </div>
          </div>
        </div>

        <div className="mb-16">
          <h1 className="text-5xl font-bold text-gray-900 leading-tight mb-4">
            Algorithmic Trading Made Simple
          </h1>
          <p className="text-lg text-gray-600">
            Deploy strategies, connect brokers, and automate your trading with institutional-grade execution.
          </p>
        </div>

        <div className="grid grid-cols-3 gap-8">
          <div>
            <p className="text-4xl font-bold text-blue-600">3+</p>
            <p className="text-sm text-gray-600 mt-1">STRATEGIES</p>
          </div>
          <div>
            <p className="text-4xl font-bold text-emerald-600">3</p>
            <p className="text-sm text-gray-600 mt-1">BROKERS</p>
          </div>
          <div>
            <p className="text-4xl font-bold text-purple-600">30+</p>
            <p className="text-sm text-gray-600 mt-1">NSE STOCKS</p>
          </div>
        </div>
      </motion.div>

      {/* Right Section - Login Form */}
      <motion.div
        initial={{ opacity: 0, x: 50 }}
        animate={{ opacity: 1, x: 0 }}
        transition={{ duration: 0.8, delay: 0.2 }}
        className="flex flex-col justify-center items-center px-6 sm:px-12 py-20 bg-white"
      >
        <div className="w-full max-w-md">
          {/* Mobile Header */}
          <div className="lg:hidden mb-10">
            <div className="flex items-center gap-2 mb-4">
              <div className="w-8 h-8 bg-gradient-to-br from-purple-500 to-blue-500 rounded-lg flex items-center justify-center">
                <span className="text-white font-bold">S</span>
              </div>
              <span className="font-bold text-xl text-gray-900">Stokr</span>
            </div>
          </div>

          {/* Form Header */}
          <div className="mb-8">
            <h3 className="text-3xl font-bold text-gray-900 mb-2">Welcome back</h3>
            <p className="text-gray-600">Sign in to access your trading dashboard</p>
          </div>

          {/* Form */}
          <form onSubmit={onSubmit} className="space-y-5">
            {/* Email Input */}
            <div>
              <label className="block text-sm font-semibold text-gray-900 mb-2">Email</label>
              <div className="relative">
                <Mail className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@example.com"
                  className="w-full pl-10 pr-4 py-3 rounded-lg border border-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent transition-all"
                  autoComplete="email"
                  required
                />
              </div>
            </div>

            {/* Password Input */}
            <div>
              <label className="block text-sm font-semibold text-gray-900 mb-2">Password</label>
              <div className="relative">
                <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Enter your password"
                  className="w-full pl-10 pr-4 py-3 rounded-lg border border-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent transition-all"
                  autoComplete="current-password"
                  required
                />
              </div>
            </div>

            {/* Error Message */}
            {error && (
              <motion.div
                initial={{ opacity: 0, y: -10 }}
                animate={{ opacity: 1, y: 0 }}
                className="p-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm"
              >
                {error}
              </motion.div>
            )}

            {/* Remember & Forgot */}
            <div className="flex items-center justify-between text-sm">
              <label className="flex items-center gap-2 text-gray-600 cursor-pointer hover:text-gray-900">
                <input type="checkbox" className="rounded" />
                Remember me
              </label>
              <Link to="/forgot-password" className="text-blue-600 hover:text-blue-700 font-medium">
                Forgot password?
              </Link>
            </div>

            {/* Submit Button */}
            <motion.button
              type="submit"
              disabled={loading}
              whileHover={{ scale: 1.01 }}
              whileTap={{ scale: 0.99 }}
              className="w-full py-3 rounded-lg bg-gradient-to-r from-blue-600 to-purple-600 text-white font-semibold hover:shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
            >
              {loading ? (
                <motion.span animate={{ rotate: 360 }} transition={{ duration: 1, repeat: Infinity }}>
                  ⟳
                </motion.span>
              ) : (
                "Sign In"
              )}
            </motion.button>
          </form>

          {/* Sign Up */}
          <p className="text-center text-gray-600 text-sm mt-6">
            Don't have an account?{" "}
            <Link to="/register" className="text-blue-600 hover:text-blue-700 font-semibold">
              Create account
            </Link>
          </p>
        </div>
      </motion.div>
    </div>
  );
}
