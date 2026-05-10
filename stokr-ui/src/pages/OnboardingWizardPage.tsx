import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { useEffect } from "react";
import {
  BadgeCheck,
  Check,
  ClipboardList,
  Mail,
  MessageCircle,
  Radio,
  Shield,
  Smartphone,
  Target,
  Wallet,
  Zap,
} from "lucide-react";
import { Link } from "react-router-dom";
import { api } from "../api/client";
import { GlassPanel } from "../components/ds/GlassPanel";
import { RiskBadge } from "../components/ds/RiskBadge";
import { SkeletonLine } from "../components/ds/SkeletonLoader";
import { useSessionStore } from "../state/session";

type Summary = {
  emailVerified: boolean;
  telegramVerified: boolean;
  whatsAppVerified: boolean;
  onboardingComplete: boolean;
  liveTradingApproved: boolean;
  zerodhaBrokerConnected: boolean;
  telegramUsername: string | null;
  whatsAppE164: string | null;
};

const STEPS = [
  "Register",
  "Email verified",
  "Telegram",
  "WhatsApp",
  "Broker · Zerodha",
  "Risk profile",
  "Strategy",
  "Mode",
  "Allocation",
  "Runtime",
];

export function OnboardingWizardPage() {
  const setSession = useSessionStore((s) => s.setSession);
  const accessToken = useSessionStore((s) => s.accessToken);
  const refreshToken = useSessionStore((s) => s.refreshToken);

  const q = useQuery({
    queryKey: ["onboarding-summary"],
    queryFn: async () => {
      const res = await api.get("/api/trader/me/onboarding-summary");
      return res.data?.data as Summary;
    },
    refetchInterval: 12_000,
    enabled: !!accessToken,
  });

  /** Sync workstation session flags when polling returns new truth — keeps nav banners accurate. */
  useEffect(() => {
    const s = q.data;
    if (!s || !accessToken || !refreshToken) return;
    const snap = useSessionStore.getState();
    if (
      snap.emailVerified === s.emailVerified &&
      snap.telegramVerified === s.telegramVerified &&
      snap.whatsAppVerified === s.whatsAppVerified &&
      snap.onboardingComplete === s.onboardingComplete &&
      snap.liveTradingApproved === s.liveTradingApproved
    ) {
      return;
    }
    setSession({
      accessToken,
      refreshToken,
      userId: snap.userId!,
      username: snap.username!,
      email: snap.email!,
      displayName: snap.displayName,
      roles: snap.roles,
      expiresInSeconds: 0,
      emailVerified: s.emailVerified,
      telegramVerified: s.telegramVerified,
      whatsAppVerified: s.whatsAppVerified,
      onboardingComplete: s.onboardingComplete,
      liveTradingApproved: s.liveTradingApproved,
    });
  }, [q.data, accessToken, refreshToken, setSession]);

  const s = q.data;
  const pct = (() => {
    if (!s) return 0;
    let n = 1;
    if (s.emailVerified) n++;
    if (s.telegramVerified) n++;
    if (s.whatsAppVerified) n++;
    if (s.zerodhaBrokerConnected) n++;
    n++; // risk profile placeholder counted when onboarding flags progress
    n++; // strategy — user-controlled
    n++; // mode
    n++; // allocation
    if (s.onboardingComplete && s.liveTradingApproved) n++; // simplified runtime gate
    return Math.round((n / STEPS.length) * 100);
  })();

  return (
    <div className="space-y-10">
      <motion.div initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }}>
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <div className="inline-flex items-center gap-2 rounded-full border border-blue-500/25 bg-blue-500/10 px-3 py-1 text-[11px] font-bold uppercase tracking-widest text-blue-200">
              <ClipboardList className="h-3 w-3" />
              Institutional onboarding
            </div>
            <h1 className="mt-4 text-3xl font-semibold tracking-tight text-white sm:text-[32px]">Operations readiness</h1>
            <p className="mt-2 max-w-2xl text-sm leading-relaxed text-neutral-400">
              Replay-safe pipelines stay cold until trader identity, Telegram binding, WhatsApp OTP, Zerodha session, risk
              profile, allocations, and admin LIVE approval align.
            </p>
          </div>
          <RiskBadge level="MEDIUM" />
        </div>

        <div className="mt-8 overflow-hidden rounded-2xl border border-neutral-800/90 bg-neutral-950/70 p-6 backdrop-blur-md">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div className="text-[11px] font-bold uppercase tracking-[0.2em] text-neutral-600">Trajectory</div>
            <span className="font-mono text-sm text-neutral-400">{pct}% complete</span>
          </div>
          <div className="relative mt-3 h-3 overflow-hidden rounded-full bg-neutral-900">
            <motion.div
              className="h-full rounded-full bg-gradient-to-r from-blue-600 via-blue-400 to-emerald-400"
              layout
              initial={{ width: 0 }}
              animate={{ width: `${pct}%` }}
              transition={{ type: "spring", stiffness: 120, damping: 18 }}
            />
          </div>
          <div className="mt-5 flex gap-3 overflow-x-auto pb-2">
            {STEPS.map((label, idx) => (
              <motion.div
                key={label}
                layout
                className={`shrink-0 rounded-xl border px-3 py-2 text-[11px] font-semibold whitespace-nowrap ${
                  pct >= ((idx + 1) / STEPS.length) * 100 ? "border-emerald-500/35 text-emerald-100" : "border-neutral-800 text-neutral-500"
                }`}
              >
                {idx + 1}. {label}
              </motion.div>
            ))}
          </div>
        </div>
      </motion.div>

      {q.isLoading ? (
        <div className="space-y-3">
          <SkeletonLine className="h-24 w-full rounded-2xl" />
          <SkeletonLine className="h-24 w-full rounded-2xl" />
        </div>
      ) : q.isError ? (
        <GlassPanel className="p-6 text-sm text-rose-200">Could not load onboarding summary.</GlassPanel>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          <ChecklistTile
              done={Boolean(s?.emailVerified)}
              title="Mailbox proof"
              desc="Operational mail path for approvals and escalation."
              icon={Mail}
            />
            <ChecklistTile
              done={Boolean(s?.telegramVerified)}
              title="Telegram binding"
              desc={s?.telegramUsername ? `@${s.telegramUsername}` : "Configure @username · deep-link bot handshake."}
              icon={Radio}
              to="/brokers"
            />
            <ChecklistTile
              done={Boolean(s?.whatsAppVerified)}
              title="WhatsApp OTP"
              desc={s?.whatsAppE164 ?? "E.164 handset for alerts & continuity."}
              icon={Smartphone}
            />
            <ChecklistTile
              done={Boolean(s?.zerodhaBrokerConnected)}
              title="Zerodha session"
              desc="Encrypted Kite OAuth · margin snapshots & health pings."
              icon={Shield}
              to="/brokers"
            />
            <ChecklistTile
              done={Boolean(s?.onboardingComplete)}
              title="Derived onboarding closure"
              desc="Automatically recomputed from verifications & broker linkage."
              icon={BadgeCheck}
            />
            <ChecklistTile
              done={Boolean(s?.liveTradingApproved)}
              title="Administrative LIVE approval"
              icon={Target}
              desc="Operations must flip live_trading_approved · arm stack + Redis still required at execution time."
              warn={!s?.liveTradingApproved}
            />

          <GlassPanel className="md:col-span-2 p-6">
            <div className="flex flex-wrap items-center gap-4">
              <div className="flex items-center gap-2 text-[11px] font-bold uppercase tracking-widest text-neutral-600">
                <Zap className="h-4 w-4 text-amber-300" /> Next actions
              </div>
              <div className="flex flex-wrap gap-2">
                <Link to="/brokers" className="rounded-lg bg-white px-4 py-2 text-xs font-bold text-neutral-950">
                  Broker console
                </Link>
                <Link to="/strategies" className="rounded-lg border border-neutral-700 px-4 py-2 text-xs font-bold text-neutral-200">
                  Strategy catalog
                </Link>
              </div>
            </div>
            <div className="mt-6 grid gap-4 sm:grid-cols-2">
              <div className="rounded-xl border border-neutral-800 bg-neutral-900/50 p-4">
                <div className="text-xs font-semibold text-white">Paper vs LIVE</div>
                <p className="mt-2 text-xs text-neutral-500">
                  Operate deterministic SIM until onboarding matrix is green · flip execution mode inside running strategy
                  shells only once checks pass.
                </p>
              </div>
              <div className="rounded-xl border border-neutral-800 bg-neutral-900/50 p-4">
                <div className="text-xs font-semibold text-white">Allocation policy</div>
                <p className="mt-2 text-xs text-neutral-500">
                  Configure multiples and max loss envelopes per instance — risk engine rejects unknown strategy keys &
                  degraded broker health.
                </p>
              </div>
            </div>
          </GlassPanel>

          {!s?.onboardingComplete ? (
            <GlassPanel className="md:col-span-2 flex items-start gap-3 border border-amber-500/25 bg-amber-500/5 p-4">
              <MessageCircle className="mt-1 h-5 w-5 text-amber-300" />
              <div>
                <div className="font-semibold text-amber-50">Path to completion</div>
                <div className="mt-2 text-xs text-amber-200/90">
                  Finish email + Telegram + WhatsApp verifications & link Zerodha before requesting LIVE approval from ops.
                  Runtime orchestration activates managed instances once flags flip.
                </div>
              </div>
            </GlassPanel>
          ) : null}

          {!s?.liveTradingApproved ? (
            <GlassPanel className="md:col-span-2 flex items-start gap-3 border border-rose-500/25 bg-rose-950/40 p-4">
              <Wallet className="mt-1 h-5 w-5 text-rose-300" />
              <div>
                <div className="font-semibold text-rose-50">LIVE awaits operations</div>
                <div className="mt-2 text-xs text-rose-200/90">
                  Administrators must explicitly approve routed broker orders regardless of onboarding completion · contact
                  your desk if stuck in paper-only mode with green checks.
                </div>
              </div>
            </GlassPanel>
          ) : (
            <GlassPanel className="md:col-span-2 flex items-start gap-3 border border-emerald-500/25 bg-emerald-950/30 p-4">
              <Check className="mt-1 h-5 w-5 text-emerald-300" />
              <div>
                <div className="font-semibold text-emerald-50">Ready class profile</div>
                <div className="mt-2 text-xs text-emerald-200/90">
                  Green stack · maintain broker session health · monitor kill switch telemetry from the operations console.
                </div>
              </div>
            </GlassPanel>
          )}
        </div>
      )}
    </div>
  );
}

function ChecklistTile({
  title,
  desc,
  done,
  icon: Icon,
  to,
  warn,
}: {
  title: string;
  desc: string;
  done: boolean;
  icon: typeof Mail;
  to?: string;
  warn?: boolean;
}) {
  const body = (
    <motion.div layout className={`h-full rounded-2xl border p-5 ${done ? "border-emerald-500/30 bg-emerald-950/25" : "border-neutral-800 bg-neutral-950/60"} ${warn ? "ring-1 ring-amber-400/35" : ""}`}>
      <div className="flex items-start gap-3">
        <div className={`rounded-xl p-2 ${done ? "bg-emerald-500/15 text-emerald-300" : "bg-neutral-900 text-neutral-400"}`}>
          <Icon className="h-5 w-5" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-[15px] font-semibold text-white">{title}</span>
            {done ? <Check className="h-4 w-4 shrink-0 text-emerald-400" /> : null}
          </div>
          <p className="mt-2 text-xs leading-relaxed text-neutral-500">{desc}</p>
          {to && !done ? (
            <Link to={to} className="mt-3 inline-block text-[11px] font-bold uppercase tracking-widest text-blue-400">
              Open lane →
            </Link>
          ) : null}
        </div>
      </div>
    </motion.div>
  );

  return <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}>{body}</motion.div>;
}
