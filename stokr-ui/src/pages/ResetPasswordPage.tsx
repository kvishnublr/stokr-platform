import { FormEvent, useMemo, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowLeft, Lock } from "lucide-react";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";

/** Mirrors backend ResetPasswordRequest @Pattern */
const STRONG_PW =
  /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9])[\S]{12,128}$/;

export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get("token")?.trim() ?? "";
  const navigate = useNavigate();

  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const inlineHint = useMemo(() => {
    if (!password) return null;
    if (password.length < 12) return "At least 12 characters.";
    if (!STRONG_PW.test(password)) {
      return "Include upper & lower case, a digit, and a symbol (no spaces).";
    }
    if (confirm && confirm !== password) return "Passwords must match.";
    return null;
  }, [password, confirm]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!token) {
      setError("Missing reset token. Open the link from your email (or paste the full URL including ?token=...).");
      return;
    }
    if (!STRONG_PW.test(password)) {
      setError(inlineHint ?? "Password does not meet requirements.");
      return;
    }
    if (password !== confirm) {
      setError("Passwords must match.");
      return;
    }
    setLoading(true);
    try {
      await api.post("/api/auth/reset-password", {
        token,
        newPassword: password,
        confirmPassword: confirm,
      });
      toast.success("Password updated - you can sign in now.");
      navigate("/login", { replace: true });
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
            <p className="mt-2 text-sm text-neutral-600">Choose a new password</p>
          </div>

          <div className="rounded-2xl border border-neutral-200 bg-white/95 p-8 shadow-xl backdrop-blur">
            {!token ? (
              <div className="space-y-4 text-center text-sm text-neutral-700">
                <p>This page needs a reset token in the URL (for example from your email).</p>
                <Link
                  to="/forgot-password"
                  className="inline-flex items-center justify-center gap-2 font-medium text-blue-600 hover:text-blue-500"
                >
                  Request a new reset
                </Link>
              </div>
            ) : (
              <form className="space-y-5" onSubmit={onSubmit}>
                <div>
                  <label className="block text-xs font-medium uppercase tracking-wide text-neutral-500">
                    New password
                  </label>
                  <div className="relative mt-2">
                    <Lock className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-neutral-500" />
                    <input
                      className="w-full rounded-lg border border-neutral-200 bg-white py-2 pl-10 pr-3 text-sm text-neutral-900 outline-none ring-blue-500/30 focus:border-blue-500/60 focus:ring-2"
                      type="password"
                      placeholder="â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      autoComplete="new-password"
                    />
                  </div>
                </div>
                <div>
                  <label className="block text-xs font-medium uppercase tracking-wide text-neutral-500">
                    Confirm password
                  </label>
                  <div className="relative mt-2">
                    <Lock className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-neutral-500" />
                    <input
                      className="w-full rounded-lg border border-neutral-200 bg-white py-2 pl-10 pr-3 text-sm text-neutral-900 outline-none ring-blue-500/30 focus:border-blue-500/60 focus:ring-2"
                      type="password"
                      placeholder="â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢"
                      value={confirm}
                      onChange={(e) => setConfirm(e.target.value)}
                      autoComplete="new-password"
                    />
                  </div>
                </div>

                {inlineHint ? (
                  <p className="text-xs text-neutral-600">{inlineHint}</p>
                ) : (
                  <p className="text-xs text-neutral-500">
                    12+ characters with upper, lower, number, and symbol (matches registration rules).
                  </p>
                )}

                {error ? <div className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

                <button
                  disabled={loading}
                  type="submit"
                  className={cn(
                    "flex w-full items-center justify-center gap-2 rounded-lg bg-neutral-900 py-2.5 text-sm font-semibold text-white transition hover:bg-neutral-800",
                    loading && "opacity-60",
                  )}
                >
                  {loading ? "Updating..." : "Update password"}
                </button>

                <p className="text-center text-sm text-neutral-500">
                  <Link className="inline-flex items-center justify-center gap-1 font-medium text-blue-600 hover:text-blue-500" to="/login">
                    <ArrowLeft className="h-4 w-4" />
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
