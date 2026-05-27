import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { ActivitySquare, AlertTriangle, Wallet } from "lucide-react";
import { Link } from "react-router-dom";
import { api } from "../api/client";
import { parseMoney } from "../lib/moneyUtils";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";
import { AnimatedKpiCard, PremiumPanel, TraderPageShell } from "../components/trader/TraderPremium";

type Dash = {
  overview: {
    realizedPnl: string;
    unrealizedPnl: string;
    openPositionCount: number;
  };
};

/** Paper / simulated exposure uses the same portfolio APIs - isolate accounts via broker configuration in production. */
export function PaperTradingPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const q = useQuery({
    queryKey: ["paper-portfolio"],
    queryFn: async () => {
      const res = await api.get("/api/portfolio/dashboard?equityPoints=60");
      return res.data?.data as Dash;
    },
  });

  const o = q.data?.overview;
  const realized = parseMoney(o?.realizedPnl);
  const unrealized = parseMoney(o?.unrealizedPnl);

  return (
    <TraderPageShell
      title="Paper trading"
      subtitle="Simulated fills and latency-aware execution share portfolio endpoints with live — verify execution mode on orders before promoting strategies."
    >
      <motion.div initial="hidden" animate="show" className="grid gap-3 md:grid-cols-3">
        <AnimatedKpiCard
          label="Realized PnL"
          loading={q.isLoading}
          value={o?.realizedPnl ?? "—"}
          pnlValue={realized}
          icon={Wallet}
          accent="bg-emerald-400"
          index={0}
        />
        <AnimatedKpiCard
          label="Unrealized PnL"
          loading={q.isLoading}
          value={o?.unrealizedPnl ?? "—"}
          pnlValue={unrealized}
          accent="bg-sky-500"
          index={1}
        />
        <AnimatedKpiCard
          label="Open positions"
          loading={q.isLoading}
          value={o?.openPositionCount != null ? String(o.openPositionCount) : "—"}
          icon={ActivitySquare}
          accent="bg-amber-400"
          index={2}
        />
      </motion.div>

      <PremiumPanel title="Simulation guardrails">
        <div
          className={cn(
            "flex items-start gap-3 rounded-xl border px-4 py-3 text-sm",
            isLight ? "border-amber-200 bg-amber-50 text-amber-900" : "border-amber-900/40 bg-amber-950/20 text-amber-100",
          )}
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 opacity-80" />
          <p>
            For emergency controls (pause strategies, kill switch) use{" "}
            <Link className="font-semibold underline underline-offset-2" to="/admin/ops">
              Admin → Operations
            </Link>{" "}
            or runtime APIs.
          </p>
        </div>
      </PremiumPanel>
    </TraderPageShell>
  );
}
