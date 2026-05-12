import { FormEvent, useState } from "react";
import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowLeft, Mail } from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [sent, setSent] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const trimmed = email.trim().toLowerCase();
    if (!trimmed || !EMAIL_RE.test(trimmed)) {
      setError("Enter a valid email address.");
      return;
    }
    setLoading(true);
    try {
      await api.post("/api/auth/forgot-password", { email: trimmed });
      setSent(true);
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
            <p className="mt-2 text-sm text-neutral-600">Reset your password</p>
          </div>

          <div className="rounded-2xl border border-neutral-200 bg-white/95 p-8 shadow-xl backdrop-blur">
            {sent ? (
              <div className="space-y-4 text-center">
                <p className="text-sm leading-relaxed text-neutral-700">
                  If an account exists for that email, we sent reset instructions. In local development, check the
                  API logs for the raw reset token when email is not configured.
                </p>
                <Link
                  to="/login"
                  className="inline-flex items-center justify-center gap-2 text-sm font-medium text-blue-600 hover:text-blue-500"
                >
                  <ArrowLeft className="h-4 w-4" />
                  Back to sign in
                </Link>
              </div>
            ) : (
              <form className="space-y-5" onSubmit={onSubmit}>
                <div>
                  <label className="block text-xs font-medium uppercase tracking-wide text-neutral-500">Email</label>
                  <div className="relative mt-2">
                    <Mail className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-neutral-500" />
                    <input
                      className="w-full rounded-lg border border-neutral-200 bg-white py-2 pl-10 pr-3 text-sm text-neutral-900 outline-none ring-blue-500/30 placeholder:text-neutral-400 focus:border-blue-500/60 focus:ring-2"
                      placeholder="you@company.com"
                      type="email"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      autoComplete="email"
                    />
                  </div>
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
                  {loading ? "Sending…" : "Send reset link"}
                </button>

                <p className="text-center text-sm text-neutral-500">
                  <Link className="font-medium text-blue-600 hover:text-blue-500" to="/login">
                    Back to sign in
                  </Link>
                </p>
              </form>
            )}
          </div>
        </motion.div>
      </div>
    </div>
  );
}
