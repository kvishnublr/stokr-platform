import { useMemo, useState, useEffect } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { useUiThemeStore } from "../state/uiTheme";

type Exposure = {
  bySymbol: { symbol: string; quantity: string; exposureNotional: string }[];
  byBrokerNotional: { brokerVendor: string; tradedNotionalApprox: string }[];
};

type ParsedSymbolExposure = {
  symbol: string;
  quantityNum: number;
  notionalNum: number;
};

function fmtNumber(value: number, fraction = 2) {
  return new Intl.NumberFormat("en-IN", {
    maximumFractionDigits: fraction,
    minimumFractionDigits: fraction,
  }).format(value);
}

export function PositionsPage(props?: { embedded?: boolean }) {
  const { embedded } = props ?? {};
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [symbolQuery, setSymbolQuery] = useState("");
  const [sideFilter, setSideFilter] = useState<"ALL" | "LONG" | "SHORT">("ALL");
  const [sortBy, setSortBy] = useState<"symbol" | "quantity" | "notional">("notional");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("desc");
  const queryClient = useQueryClient();

  // Invalidate position data on SSE position/ops events for near-realtime updates
  useEffect(() => {
    const controller = new AbortController();
    const token = localStorage.getItem("accessToken");
    if (!token) return;
    let buffer = "";
    fetch("/api/admin/operations/stream", {
      headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
      signal: controller.signal,
    })
      .then(async (res) => {
        if (!res.ok || !res.body) return;
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let sep: number;
          while ((sep = buffer.indexOf("\n\n")) >= 0) {
            const frame = buffer.slice(0, sep).trim();
            buffer = buffer.slice(sep + 2);
            if (frame) {
              const isPositionEvent = frame.includes("event: tick") || frame.includes("event:tick")
                || frame.includes("event: ops_realtime") || frame.includes("event:ops_realtime");
              if (isPositionEvent) {
                void queryClient.invalidateQueries({ queryKey: ["portfolio-exposure"] });
              }
            }
          }
        }
      })
      .catch(() => {});
    return () => controller.abort();
  }, [queryClient]);

  const q = useQuery({
    queryKey: ["portfolio-exposure"],
    queryFn: async () => {
      const res = await api.get("/api/portfolio/exposure");
      return res.data?.data as Exposure;
    },
    refetchInterval: 10_000,
  });

  const parsedBySymbol = useMemo<ParsedSymbolExposure[]>(() => {
    return (q.data?.bySymbol ?? []).map((r) => ({
      symbol: r.symbol,
      quantityNum: Number(r.quantity || 0),
      notionalNum: Number(r.exposureNotional || 0),
    }));
  }, [q.data?.bySymbol]);

  const filteredRows = useMemo(() => {
    let rows = parsedBySymbol;
    if (symbolQuery.trim()) {
      const needle = symbolQuery.trim().toUpperCase();
      rows = rows.filter((r) => r.symbol.toUpperCase().includes(needle));
    }
    if (sideFilter === "LONG") rows = rows.filter((r) => r.quantityNum > 0);
    if (sideFilter === "SHORT") rows = rows.filter((r) => r.quantityNum < 0);
    return rows;
  }, [parsedBySymbol, symbolQuery, sideFilter]);

  const sortedRows = useMemo(() => {
    const arr = [...filteredRows];
    arr.sort((a, b) => {
      let cmp = 0;
      if (sortBy === "symbol") cmp = a.symbol.localeCompare(b.symbol);
      if (sortBy === "quantity") cmp = a.quantityNum - b.quantityNum;
      if (sortBy === "notional") cmp = a.notionalNum - b.notionalNum;
      return sortDir === "asc" ? cmp : -cmp;
    });
    return arr;
  }, [filteredRows, sortBy, sortDir]);

  const summary = useMemo(() => {
    const totalSymbols = parsedBySymbol.length;
    const netQty = parsedBySymbol.reduce((s, r) => s + r.quantityNum, 0);
    const grossNotional = parsedBySymbol.reduce((s, r) => s + Math.abs(r.notionalNum), 0);
    const top = [...parsedBySymbol].sort((a, b) => Math.abs(b.notionalNum) - Math.abs(a.notionalNum))[0];
    return { totalSymbols, netQty, grossNotional, top };
  }, [parsedBySymbol]);

  const brokerRows = useMemo(() => {
    const rows = (q.data?.byBrokerNotional ?? []).map((r) => ({
      brokerVendor: r.brokerVendor,
      notionalNum: Number(r.tradedNotionalApprox || 0),
    }));
    const total = rows.reduce((s, r) => s + Math.abs(r.notionalNum), 0);
    return rows
      .sort((a, b) => Math.abs(b.notionalNum) - Math.abs(a.notionalNum))
      .map((r) => ({ ...r, sharePct: total > 0 ? (Math.abs(r.notionalNum) / total) * 100 : 0 }));
  }, [q.data?.byBrokerNotional]);

  function toggleSort(next: "symbol" | "quantity" | "notional") {
    if (sortBy === next) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
      return;
    }
    setSortBy(next);
    setSortDir(next === "symbol" ? "asc" : "desc");
  }

  function downloadCsv(filename: string, headers: string[], rows: (string | number)[][]) {
    const escape = (v: string | number) => `"${String(v).replace(/"/g, '""')}"`;
    const lines = [headers.map(escape).join(","), ...rows.map((r) => r.map(escape).join(","))];
    const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }

  const cardCls = isLight
    ? "rounded-2xl border border-neutral-200 bg-white p-5 shadow-sm"
    : "rounded-2xl border border-neutral-800 bg-neutral-950/60 p-5";
  const mutedCls = isLight ? "text-neutral-600" : "text-neutral-400";
  const headingCls = isLight ? "text-neutral-900" : "text-white";
  const tableHeadCls = isLight ? "text-neutral-500" : "text-neutral-400";
  const rowBorderCls = isLight ? "border-t border-neutral-100" : "border-t border-neutral-900";
  const inputCls = isLight
    ? "rounded-lg border border-neutral-200 bg-white px-3 py-2 text-sm text-neutral-900 shadow-sm outline-none transition focus:border-blue-500"
    : "rounded-lg border border-neutral-700 bg-neutral-900 px-3 py-2 text-sm text-neutral-100 shadow-sm outline-none transition focus:border-blue-500";

  return (
    <div className="space-y-8">
      {!embedded ? (
        <div>
          <h1 className={isLight ? "text-2xl font-semibold tracking-tight text-neutral-900" : "text-2xl font-semibold tracking-tight text-white"}>Positions & exposure</h1>
          <p className={isLight ? "mt-2 max-w-2xl text-sm text-neutral-600" : "mt-2 max-w-2xl text-sm text-neutral-400"}>
            Symbol exposure from rebuilt ledger positions; broker notionals aggregate historical traded volume by routing vendor.
          </p>
        </div>
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <div className={cardCls}>
          <div className={`text-xs uppercase tracking-wide ${mutedCls}`}>Tracked symbols</div>
          <div className={`mt-2 text-2xl font-semibold ${headingCls}`}>{summary.totalSymbols}</div>
        </div>
        <div className={cardCls}>
          <div className={`text-xs uppercase tracking-wide ${mutedCls}`}>Net quantity</div>
          <div className={`mt-2 text-2xl font-semibold ${headingCls}`}>{fmtNumber(summary.netQty, 0)}</div>
        </div>
        <div className={cardCls}>
          <div className={`text-xs uppercase tracking-wide ${mutedCls}`}>Gross notional</div>
          <div className={`mt-2 text-2xl font-semibold ${headingCls}`}>INR {fmtNumber(summary.grossNotional)}</div>
        </div>
        <div className={cardCls}>
          <div className={`text-xs uppercase tracking-wide ${mutedCls}`}>Largest exposure</div>
          <div className={`mt-2 text-base font-semibold ${headingCls}`}>{summary.top?.symbol ?? "—"}</div>
          <div className={`text-xs ${mutedCls}`}>INR {fmtNumber(Math.abs(summary.top?.notionalNum ?? 0))}</div>
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <div className={cardCls}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className={`text-sm font-medium ${headingCls}`}>By symbol</div>
            <button
              type="button"
              className={isLight ? "rounded-md border border-neutral-200 px-2.5 py-1 text-xs font-semibold text-neutral-700 hover:bg-neutral-50" : "rounded-md border border-neutral-700 px-2.5 py-1 text-xs font-semibold text-neutral-200 hover:bg-neutral-800"}
              onClick={() =>
                downloadCsv(
                  "positions-by-symbol.csv",
                  ["Symbol", "Quantity", "Notional"],
                  sortedRows.map((r) => [r.symbol, r.quantityNum, r.notionalNum]),
                )
              }
            >
              Export CSV
            </button>
          </div>

          <div className="mt-3 flex flex-wrap items-center gap-2">
            <input className={inputCls} placeholder="Search symbol..." value={symbolQuery} onChange={(e) => setSymbolQuery(e.target.value)} />
            {(["ALL", "LONG", "SHORT"] as const).map((k) => (
              <button
                key={k}
                type="button"
                onClick={() => setSideFilter(k)}
                className={sideFilter === k ? "rounded-full border border-blue-500 bg-blue-50 px-2.5 py-1 text-xs font-semibold text-blue-700" : isLight ? "rounded-full border border-neutral-200 px-2.5 py-1 text-xs font-semibold text-neutral-600" : "rounded-full border border-neutral-700 px-2.5 py-1 text-xs font-semibold text-neutral-300"}
              >
                {k}
              </button>
            ))}
          </div>

          <div className="mt-4 max-h-[540px] overflow-auto">
            <table className="w-full text-left text-xs">
              <thead className={`${tableHeadCls} sticky top-0 ${isLight ? "bg-white" : "bg-neutral-950"}`}>
                <tr>
                  <th className="pb-2 pr-2">
                    <button type="button" className="font-semibold" onClick={() => toggleSort("symbol")}>Symbol</button>
                  </th>
                  <th className="pb-2 pr-2">
                    <button type="button" className="font-semibold" onClick={() => toggleSort("quantity")}>Qty</button>
                  </th>
                  <th className="pb-2 pr-2">
                    <button type="button" className="font-semibold" onClick={() => toggleSort("notional")}>Notional</button>
                  </th>
                </tr>
              </thead>
              <tbody className={isLight ? "text-neutral-700" : "text-neutral-200"}>
                {q.isLoading ? (
                  <tr><td className="py-3 text-xs text-neutral-500" colSpan={3}>Loading exposure...</td></tr>
                ) : sortedRows.length === 0 ? (
                  <tr><td className="py-3 text-xs text-neutral-500" colSpan={3}>No symbols match current filters.</td></tr>
                ) : sortedRows.map((r) => (
                  <tr key={r.symbol} className={rowBorderCls}>
                    <td className="py-2 font-mono">{r.symbol}</td>
                    <td className={`py-2 font-mono ${r.quantityNum > 0 ? "text-emerald-600" : r.quantityNum < 0 ? "text-rose-600" : ""}`}>
                      {fmtNumber(r.quantityNum, 0)}
                    </td>
                    <td className="py-2 font-mono">INR {fmtNumber(r.notionalNum)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div className={cardCls}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className={`text-sm font-medium ${headingCls}`}>Broker traded notional (approx)</div>
            <button
              type="button"
              className={isLight ? "rounded-md border border-neutral-200 px-2.5 py-1 text-xs font-semibold text-neutral-700 hover:bg-neutral-50" : "rounded-md border border-neutral-700 px-2.5 py-1 text-xs font-semibold text-neutral-200 hover:bg-neutral-800"}
              onClick={() =>
                downloadCsv(
                  "broker-notional.csv",
                  ["Broker", "Notional", "SharePct"],
                  brokerRows.map((r) => [r.brokerVendor, r.notionalNum, r.sharePct.toFixed(2)]),
                )
              }
            >
              Export CSV
            </button>
          </div>

          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className={tableHeadCls}>
                <tr>
                  <th className="pb-2 pr-2">Broker</th>
                  <th className="pb-2 pr-2">Notional</th>
                  <th className="pb-2">Share</th>
                </tr>
              </thead>
              <tbody className={isLight ? "text-neutral-700" : "text-neutral-200"}>
                {q.isLoading ? (
                  <tr><td className="py-3 text-xs text-neutral-500" colSpan={3}>Loading broker exposure...</td></tr>
                ) : brokerRows.length === 0 ? (
                  <tr><td className="py-3 text-xs text-neutral-500" colSpan={3}>No broker exposure data available.</td></tr>
                ) : brokerRows.map((r) => (
                  <tr key={r.brokerVendor} className={rowBorderCls}>
                    <td className="py-2 font-mono">{r.brokerVendor}</td>
                    <td className="py-2 font-mono">INR {fmtNumber(r.notionalNum)}</td>
                    <td className="py-2">
                      <div className="flex items-center gap-2">
                        <div className={isLight ? "h-1.5 w-28 rounded bg-neutral-100" : "h-1.5 w-28 rounded bg-neutral-800"}>
                          <div className="h-1.5 rounded bg-blue-500" style={{ width: `${Math.max(4, Math.min(100, r.sharePct))}%` }} />
                        </div>
                        <span className="font-mono text-[11px]">{fmtNumber(r.sharePct, 1)}%</span>
                      </div>
                    </td>
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
