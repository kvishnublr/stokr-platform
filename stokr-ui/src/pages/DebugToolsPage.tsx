import { ExternalLink } from "lucide-react";

const links = [
  { label: "OpenAPI JSON", href: "/v3/api-docs" },
  { label: "Swagger UI", href: "/swagger-ui" },
  { label: "Health", href: "/actuator/health" },
  { label: "Prometheus", href: "/actuator/prometheus" },
];

export function DebugToolsPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-white">Execution debugging</h1>
        <p className="mt-2 text-sm text-neutral-400">
          Quick links for correlation tracing (via <span className="font-mono">X-Correlation-Id</span>), journal APIs,
          and platform observability. Use backtest run detail to journal timeline for replay chains.
        </p>
      </div>
      <ul className="space-y-2">
        {links.map((l) => (
          <li key={l.href}>
            <a
              href={l.href}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-2 text-sm text-blue-400 hover:text-blue-300"
            >
              {l.label}
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
          </li>
        ))}
      </ul>
      <div className="rounded-xl border border-neutral-800 bg-neutral-950/60 p-4 text-xs text-neutral-400">
        Authenticated JSON APIs require a bearer token - open these from the same origin while logged in (Vite proxies{" "}
        <span className="font-mono">/api</span>) or paste a token into Swagger Authorize.
      </div>
    </div>
  );
}
