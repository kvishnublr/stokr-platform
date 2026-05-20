import { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import { toast } from "sonner";
import { Area, AreaChart, ResponsiveContainer } from "recharts";
import { useNavigate } from "react-router-dom";
import { cn } from "../../lib/utils";
import { IST_LOCALE, IST_ZONE } from "../../lib/dateUtils";
import { useUiThemeStore } from "../../state/uiTheme";

const SPARK = Array.from({ length: 14 }, (_, i) => ({ x: i, y: 40 + Math.sin(i * 0.6) * 12 + i * 1.5 }));

function ClockTick({ className }: { className?: string }) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  return (
    <span className={cn("font-mono text-[11px]", className)}>
      {now.toLocaleTimeString(IST_LOCALE, { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false, timeZone: IST_ZONE })}{" "}
       ·  {now.toLocaleDateString(IST_LOCALE, { weekday: "short", month: "short", day: "numeric", timeZone: IST_ZONE })}
    </span>
  );
}

export function TraderAccountCard({
  equityDisplay,
  marginDisplay,
  brokerConnected = false,
  onRefreshRequested,
}: {
  equityDisplay: string;
  marginDisplay?: string;
  brokerConnected?: boolean;
  onRefreshRequested?: () => void | Promise<void>;
}) {
  const navigate = useNavigate();
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const margin = marginDisplay ?? "-";
  const [depositOpen, setDepositOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const depositPollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const parsedAmount = useMemo(() => Number(amount), [amount]);
  const amountValid = Number.isFinite(parsedAmount) && parsedAmount > 0;

  useEffect(() => {
    return () => {
      if (depositPollRef.current) {
        clearInterval(depositPollRef.current);
        depositPollRef.current = null;
      }
    };
  }, []);

  function openDepositPopup(depositAmount: string) {
    const popup = window.open(
      "https://kite.zerodha.com/funds",
      "stokr_deposit_funds",
      "popup=yes,width=1100,height=780,scrollbars=yes,resizable=yes,status=no,toolbar=no,menubar=no,location=yes",
    );
    if (!popup) {
      toast.error("Popup blocked. Opening broker funds in current tab.");
      navigate(`/brokers?depositAmount=${encodeURIComponent(depositAmount)}`);
      return;
    }
    toast.message("Deposit window opened. Complete funding, then close it to refresh margin.");
    if (depositPollRef.current) {
      clearInterval(depositPollRef.current);
    }
    depositPollRef.current = setInterval(() => {
      if (!popup.closed) return;
      if (depositPollRef.current) {
        clearInterval(depositPollRef.current);
        depositPollRef.current = null;
      }
      toast.success("Refreshing margin snapshot...");
      void onRefreshRequested?.();
      navigate(`/brokers?depositAmount=${encodeURIComponent(depositAmount)}`);
    }, 700);
  }

  return (
    <div>
      <div
        className={cn(
          "rounded-2xl p-4 ring-1 transition-shadow",
          isLight
            ? "border border-neutral-200/80 bg-gradient-to-b from-white via-neutral-50/70 to-white shadow-[0_10px_28px_-18px_rgba(15,23,42,0.28)] ring-neutral-100/90"
            : "border border-white/[0.08] bg-gradient-to-b from-neutral-900/90 to-neutral-950 shadow-[inset_0_1px_0_0_rgba(255,255,255,0.04)] ring-white/[0.04]",
        )}
      >
        <div className={cn("text-[10px] font-bold uppercase tracking-[0.2em]", isLight ? "text-neutral-500" : "text-neutral-500")}>
          Total equity
        </div>
        <div className={cn("mt-1 font-mono text-[34px] font-semibold tracking-tight leading-none", isLight ? "text-neutral-900" : "text-white")}>
          {equityDisplay}
        </div>
        <div className="mt-3 h-10 w-full opacity-90">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={SPARK}>
              <defs>
                <linearGradient id="eqSpark" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#34d399" stopOpacity={isLight ? 0.42 : 0.35} />
                  <stop offset="100%" stopColor="#34d399" stopOpacity={0} />
                </linearGradient>
              </defs>
              <Area type="monotone" dataKey="y" stroke="#10b981" strokeWidth={1.5} fill="url(#eqSpark)" />
            </AreaChart>
          </ResponsiveContainer>
        </div>
        <div className={cn("mt-3 gap-2 border-t pt-3", isLight ? "border-neutral-200" : "border-white/[0.06]")}>
          <div className={cn("text-[11px] uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}>Available margin</div>
          <motion.div
            initial={{ opacity: 0.8, scale: 0.985 }}
            animate={{ opacity: [0.9, 1, 0.9], scale: [0.995, 1.01, 0.995] }}
            transition={{ duration: 2.8, repeat: Infinity, ease: "easeInOut" }}
            className={cn(
              "mt-1 inline-flex rounded-xl border px-2 py-1.5 font-mono text-[16px] font-bold leading-none tracking-tight",
              isLight
                ? "border-emerald-200 bg-emerald-50 text-emerald-800"
                : "border-emerald-500/30 bg-emerald-500/10 text-emerald-200",
            )}
          >
            {margin}
          </motion.div>
        </div>
        <button
          type="button"
          onClick={() => setDepositOpen(true)}
          className="mt-3 flex w-full items-center justify-center rounded-2xl bg-blue-600 py-2.5 text-[12px] font-bold text-white shadow-[0_10px_24px_-12px_rgba(37,99,235,0.6)] transition hover:bg-blue-500"
        >
          Deposit funds
        </button>
      </div>
      {depositOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
          <div
            className={cn(
              "w-full max-w-md rounded-2xl border p-5 shadow-xl",
              isLight ? "border-neutral-200 bg-white" : "border-neutral-700 bg-neutral-900",
            )}
          >
            <div className={cn("text-sm font-semibold", isLight ? "text-neutral-900" : "text-white")}>Add broker funds</div>
            <div className={cn("mt-2 text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
              Available broker margin: <span className={cn("font-mono font-semibold", isLight ? "text-neutral-900" : "text-neutral-100")}>{margin}</span>
            </div>
            <div className={cn("mt-1 text-xs", isLight ? "text-neutral-500" : "text-neutral-500")}>
              {brokerConnected
                ? "Funding happens at broker side. Enter amount, then continue to Broker Connect."
                : "No connected broker account found. Connect broker first, then add funds."}
            </div>
            <label className={cn("mt-4 block text-[11px] font-semibold uppercase tracking-wide", isLight ? "text-neutral-600" : "text-neutral-400")}>
              Deposit amount (INR)
            </label>
            <input
              type="number"
              min={1}
              step={1}
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="e.g. 50000"
              className={cn(
                "mt-2 w-full rounded-xl border px-3 py-2 text-sm outline-none",
                isLight
                  ? "border-neutral-200 bg-white text-neutral-900 placeholder:text-neutral-400"
                  : "border-neutral-700 bg-neutral-950 text-white placeholder:text-neutral-500",
              )}
            />
            <div className="mt-4 flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={() => setDepositOpen(false)}
                className={cn(
                  "rounded-xl border px-3 py-2 text-xs font-semibold",
                  isLight ? "border-neutral-200 text-neutral-700 hover:bg-neutral-50" : "border-neutral-700 text-neutral-200 hover:bg-neutral-800",
                )}
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={!amountValid}
                onClick={() => {
                  const q = amount.trim();
                  setDepositOpen(false);
                  openDepositPopup(q);
                }}
                className="rounded-xl bg-blue-600 px-3 py-2 text-xs font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50"
              >
                Continue
              </button>
            </div>
          </div>
        </div>
      ) : null}
      <div
        className={cn(
          "mt-3 flex flex-wrap items-center justify-between gap-1 px-1 text-[10px]",
          isLight ? "text-neutral-500" : "text-neutral-600",
        )}
      >
        <span>Local terminal</span>
        <ClockTick className={isLight ? "text-neutral-500" : "text-neutral-400"} />
      </div>
    </div>
  );
}
