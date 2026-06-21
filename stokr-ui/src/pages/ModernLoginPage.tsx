import { FormEvent, useState } from "react";
import { Navigate } from "react-router-dom";
import { Lock, Mail } from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import type { AuthPayload } from "../state/session";
import { useSessionStore } from "../state/session";

export function ModernLoginPage() {
  const token = useSessionStore((s) => s.accessToken);
  const setSession = useSessionStore((s) => s.setSession);
  const [principal, setPrincipal] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  if (token) return <Navigate to="/" replace />;

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
    <div className="min-h-screen flex bg-gradient-to-br from-purple-200 via-purple-100 to-cyan-100">
      {/* Left Sidebar - Branding */}
      <div className="hidden lg:flex lg:w-1/2 flex-col justify-center items-start p-16 text-dark">
        <div className="max-w-lg">
          <div className="flex items-center gap-4 mb-10">
            <div className="w-16 h-16 bg-gradient-to-br from-purple-500 to-indigo-600 rounded-3xl flex items-center justify-center shadow-lg">
              <span className="text-3xl font-bold text-white">S</span>
            </div>
            <div>
              <h1 className="text-2xl font-bold text-gray-900">Stokr</h1>
              <p className="text-xs text-gray-600 tracking-widest font-medium">AURORA PRO</p>
            </div>
          </div>

          <h2 className="text-5xl font-bold text-gray-900 mb-8 leading-tight">
            Algorithmic Trading<br />Made Simple
          </h2>

          <p className="text-lg text-gray-700 mb-16 leading-relaxed">
            Deploy strategies, connect brokers, and automate your trading with institutional-grade execution.
          </p>

          <div className="flex gap-16">
            <div>
              <p className="text-4xl font-bold text-indigo-600">3 +</p>
              <p className="text-xs text-gray-600 uppercase tracking-wide font-semibold">Strategies</p>
            </div>
            <div>
              <p className="text-4xl font-bold text-indigo-600">3</p>
              <p className="text-xs text-gray-600 uppercase tracking-wide font-semibold">Brokers</p>
            </div>
            <div>
              <p className="text-4xl font-bold text-indigo-600">30 +</p>
              <p className="text-xs text-gray-600 uppercase tracking-wide font-semibold">NSE Stocks</p>
            </div>
          </div>
        </div>
      </div>

      {/* Right Side - Login Form */}
      <div className="w-full lg:w-1/2 flex items-center justify-center p-6 sm:p-8">
        <div className="w-full max-w-md bg-white rounded-3xl shadow-2xl p-8 sm:p-10">
          <div className="text-center mb-10">
            <div className="flex justify-center mb-4">
              <div className="w-12 h-12 bg-gradient-to-br from-purple-500 to-indigo-600 rounded-2xl flex items-center justify-center">
                <span className="text-xl font-bold text-white">S</span>
              </div>
            </div>
            <h3 className="text-3xl font-bold text-gray-900 mb-3">Stokr</h3>
            <p className="text-gray-600 text-base">Sign in to access your trading dashboard</p>
          </div>

          <form onSubmit={onSubmit} className="space-y-6">
            {error && (
              <div className="p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm">
                {error}
              </div>
            )}

            {/* Email Input */}
            <div>
              <label className="block text-sm font-semibold text-gray-800 mb-2">Email</label>
              <div className="relative">
                <Mail className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
                <input
                  type="email"
                  value={principal}
                  onChange={(e) => setPrincipal(e.target.value)}
                  placeholder="you@example.com"
                  className="w-full pl-12 pr-4 py-3 rounded-xl border border-gray-300 bg-gray-50 text-gray-900 placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition"
                  autoComplete="username"
                  required
                />
              </div>
            </div>

            {/* Password Input */}
            <div>
              <label className="block text-sm font-semibold text-gray-800 mb-2">Password</label>
              <div className="relative">
                <Lock className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Enter your password"
                  className="w-full pl-12 pr-4 py-3 rounded-xl border border-gray-300 bg-gray-50 text-gray-900 placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition"
                  autoComplete="current-password"
                  required
                />
              </div>
            </div>

            {/* Submit Button */}
            <button
              type="submit"
              disabled={loading}
              className="w-full py-3 rounded-2xl bg-indigo-600 text-white font-semibold hover:bg-indigo-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed mt-8"
            >
              {loading ? "Signing in..." : "Sign In"}
            </button>

            {/* Links */}
            <div className="flex items-center justify-between text-sm">
              <a href="/forgot-password" className="text-indigo-600 hover:text-indigo-700 font-medium">
                Forgot password?
              </a>
              <a href="/register" className="text-indigo-600 hover:text-indigo-700 font-medium">
                Create account
              </a>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
