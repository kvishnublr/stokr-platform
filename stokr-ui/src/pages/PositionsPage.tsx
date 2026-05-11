import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { useUiThemeStore } from "../state/uiTheme";

type Exposure = {
  bySymbol: { symbol: string; quantity: string; exposureNotional: string }[];
  byBrokerNotional: { brokerVendor: string; tradedNotionalApprox: string }[];
};

export function PositionsPage(props?: { embedded?: boolean }) {
  const { embedded } = props ?? {};
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const q = useQuery({
    queryKey: ["portfolio-exposure"],
    queryFn: async () => {
      const res = await api.get("/api/portfolio/exposure");
      return res.data?.data as Exposure;
    },
  });

  return (
    <div className="space-y-8">
      {!embedded ? (
        <div>
          <h1 className={isLight ? "text-2xl font-semibold tracking-tight text-neutral-900" : "text-2xl font-semibold tracking-tight text-white"}>Positions & exposure</h1>
          <p className={isLight ? "mt-2 max-w-2xl text-sm text-neutral-600" : "mt-2 max-w-2xl text-sm text-neutral-400"}>
            Symbol exposure from rebuilt ledger positions; broker notionals aggregate historical traded volume by routing
            vendor.
          </p>
        </div>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-2">
        <div className={isLight ? "rounded-2xl border border-neutral-200 bg-white p-5" : "rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5"}>
          <div className={isLight ? "text-sm font-medium text-neutral-900" : "text-sm font-medium text-white"}>By symbol</div>
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className={isLight ? "text-neutral-500" : "text-neutral-500"}>
                <tr>
                  <th className="pb-2">Symbol</th>
                  <th className="pb-2">Qty</th>
                  <th className="pb-2">Notional</th>
                </tr>
              </thead>
              <tbody className={isLight ? "text-neutral-700" : "text-neutral-200"}>
                {(q.data?.bySymbol ?? []).map((r) => (
                  <tr key={r.symbol} className={isLight ? "border-t border-neutral-100" : "border-t border-neutral-900"}>
                    <td className="py-2 font-mono">{r.symbol}</td>
                    <td className="py-2 font-mono">{r.quantity}</td>
                    <td className="py-2 font-mono">{r.exposureNotional}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div className={isLight ? "rounded-2xl border border-neutral-200 bg-white p-5" : "rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5"}>
          <div className={isLight ? "text-sm font-medium text-neutral-900" : "text-sm font-medium text-white"}>Broker traded notional (approx)</div>
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className={isLight ? "text-neutral-500" : "text-neutral-500"}>
                <tr>
                  <th className="pb-2">Broker</th>
                  <th className="pb-2">Notional</th>
                </tr>
              </thead>
              <tbody className={isLight ? "text-neutral-700" : "text-neutral-200"}>
                {(q.data?.byBrokerNotional ?? []).map((r) => (
                  <tr key={r.brokerVendor} className={isLight ? "border-t border-neutral-100" : "border-t border-neutral-900"}>
                    <td className="py-2 font-mono">{r.brokerVendor}</td>
                    <td className="py-2 font-mono">{r.tradedNotionalApprox}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}
