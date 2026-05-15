import { Link } from "react-router-dom";

const items: { title: string; detail: string; to: string }[] = [
  { title: "Broker controls", detail: "Reconnect, session refresh, feed pause/resume per vendor rail.", to: "/admin/ops" },
  { title: "Replay controls", detail: "Replay queue visibility, diagnostics, and job lifecycle.", to: "/admin/replay" },
  { title: "Scanner surface", detail: "Strategy scanner grid and process telemetry.", to: "/admin/ops" },
  { title: "OMS & execution", detail: "Order monitor grid and execution timelines.", to: "/admin/execution" },
  { title: "LIVE trading posture", detail: "Readiness gate + LIVE arm / kill switch in global header.", to: "/admin/ops" },
];

export function AdminControlsPage() {
  return (
    <div className="space-y-3 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Settings & operational controls</h1>
        <p className="mt-1 text-xs text-muted-foreground">
          Dense controls remain on the operations cockpit; this page maps control domains to their live surfaces.
        </p>
      </div>
      <ul className="space-y-2">
        {items.map((x) => (
          <li key={x.title}>
            <Link
              to={x.to}
              className="block rounded-lg border border-border bg-card px-3 py-2 transition hover:border-primary/40 hover:bg-background/60"
            >
              <div className="text-sm font-semibold text-foreground">{x.title}</div>
              <div className="mt-0.5 text-[11px] text-muted-foreground">{x.detail}</div>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
