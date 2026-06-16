import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { AnimatePresence, motion } from "framer-motion";
import { Pin, PinOff, Search, Star } from "lucide-react";
import { SafetyDiagnosticsLaunchLink } from "./SafetyDiagnosticsLaunchLink";
import { ADMIN_FLAT_NAV, type AdminNavItem } from "../../../admin/navigation";
import { useAdminWorkspaceStore } from "../../../admin/adminWorkspaceStore";
import { cn } from "../../../lib/utils";

function scoreItem(item: AdminNavItem, q: string): number {
  if (!q) return item.tier === "critical" ? 3 : item.tier === "frequent" ? 2 : 1;
  const needle = q.toLowerCase();
  let score = 0;
  if (item.label.toLowerCase().includes(needle)) score += 5;
  if (item.description.toLowerCase().includes(needle)) score += 3;
  if (item.keywords?.some((k) => k.includes(needle))) score += 4;
  if (item.to.toLowerCase().includes(needle)) score += 2;
  return score;
}

export function AdminCommandPalette() {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const navigate = useNavigate();
  const pinnedRoutes = useAdminWorkspaceStore((s) => s.pinnedRoutes);
  const recentRoutes = useAdminWorkspaceStore((s) => s.recentRoutes);
  const pinRoute = useAdminWorkspaceStore((s) => s.pinRoute);
  const unpinRoute = useAdminWorkspaceStore((s) => s.unpinRoute);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((v) => !v);
      }
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const results = useMemo(() => {
    const q = query.trim();
    return ADMIN_FLAT_NAV.filter((item) => !item.deprecated)
      .map((item) => ({ item, score: scoreItem(item, q) }))
      .filter(({ score }) => (q ? score > 0 : true))
      .sort((a, b) => b.score - a.score)
      .slice(0, 12)
      .map(({ item }) => item);
  }, [query]);

  const pinnedItems = useMemo(
    () => pinnedRoutes.map((r) => ADMIN_FLAT_NAV.find((i) => i.to === r)).filter(Boolean) as AdminNavItem[],
    [pinnedRoutes],
  );

  const recentItems = useMemo(
    () => recentRoutes.map((r) => ADMIN_FLAT_NAV.find((i) => i.to === r)).filter(Boolean) as AdminNavItem[],
    [recentRoutes],
  );

  function go(to: string) {
    setOpen(false);
    setQuery("");
    navigate(to);
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="hidden items-center gap-2 rounded-lg border border-neutral-800 bg-neutral-900/60 px-3 py-1.5 text-xs text-neutral-400 transition hover:border-neutral-700 hover:text-neutral-200 md:flex dark:border-neutral-800"
      >
        <Search className="h-3.5 w-3.5" />
        <span>Search admin…</span>
        <kbd className="rounded border border-neutral-700 bg-neutral-950 px-1.5 py-0.5 font-mono text-[10px]">⌘K</kbd>
      </button>

      <AnimatePresence>
        {open ? (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="fixed inset-0 z-[100] bg-black/60 backdrop-blur-sm"
              onClick={() => setOpen(false)}
            />
            <motion.div
              initial={{ opacity: 0, scale: 0.98, y: -8 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.98, y: -8 }}
              className="fixed left-1/2 top-[12vh] z-[101] w-[min(640px,calc(100vw-2rem))] -translate-x-1/2 overflow-hidden rounded-2xl border border-neutral-800 bg-neutral-950 shadow-2xl"
            >
              <div className="flex items-center gap-2 border-b border-neutral-800 px-4 py-3">
                <Search className="h-4 w-4 text-neutral-500" />
                <input
                  autoFocus
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="Jump to any admin surface…"
                  className="flex-1 bg-transparent text-sm text-neutral-100 outline-none placeholder:text-neutral-600"
                />
              </div>
              <div className="max-h-[min(60vh,480px)] overflow-y-auto p-2">
                {!query && pinnedItems.length > 0 ? (
                  <PaletteSection title="Pinned">
                    {pinnedItems.map((item) => (
                      <PaletteRow
                        key={item.to}
                        item={item}
                        pinned
                        onGo={() => go(item.to)}
                        onTogglePin={() => unpinRoute(item.to)}
                      />
                    ))}
                  </PaletteSection>
                ) : null}
                {!query && recentItems.length > 0 ? (
                  <PaletteSection title="Recent">
                    {recentItems.map((item) => (
                      <PaletteRow
                        key={item.to}
                        item={item}
                        pinned={pinnedRoutes.includes(item.to)}
                        onGo={() => go(item.to)}
                        onTogglePin={() =>
                          pinnedRoutes.includes(item.to) ? unpinRoute(item.to) : pinRoute(item.to)
                        }
                      />
                    ))}
                  </PaletteSection>
                ) : null}
                <PaletteSection title={query ? "Results" : "All operations"}>
                  {results.map((item) => (
                    <PaletteRow
                      key={item.to}
                      item={item}
                      pinned={pinnedRoutes.includes(item.to)}
                      onGo={() => go(item.to)}
                      onTogglePin={() =>
                        pinnedRoutes.includes(item.to) ? unpinRoute(item.to) : pinRoute(item.to)
                      }
                    />
                  ))}
                </PaletteSection>
              </div>
              <div className="border-t border-neutral-800 px-4 py-2 text-[10px] text-neutral-500">
                ↑↓ navigate · Enter open · Esc close · Pin with star
              </div>
            </motion.div>
          </>
        ) : null}
      </AnimatePresence>
    </>
  );
}

function PaletteSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mb-2">
      <div className="px-2 py-1.5 text-[10px] font-bold uppercase tracking-widest text-neutral-500">{title}</div>
      <div className="space-y-0.5">{children}</div>
    </div>
  );
}

function PaletteRow({
  item,
  pinned,
  onGo,
  onTogglePin,
}: {
  item: AdminNavItem;
  pinned: boolean;
  onGo: () => void;
  onTogglePin: () => void;
}) {
  const Icon = item.icon;
  return (
    <div className="group flex items-center gap-1 rounded-lg hover:bg-neutral-900">
      <button type="button" onClick={onGo} className="flex min-w-0 flex-1 items-center gap-3 px-3 py-2.5 text-left">
        <Icon className="h-4 w-4 shrink-0 text-blue-400" />
        <div className="min-w-0">
          <div className="truncate text-sm font-medium text-neutral-100">{item.label}</div>
          <div className="truncate text-xs text-neutral-500">{item.description}</div>
        </div>
      </button>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          onTogglePin();
        }}
        className="mr-1 rounded p-1.5 text-neutral-600 opacity-0 transition hover:bg-neutral-800 hover:text-amber-400 group-hover:opacity-100"
        title={pinned ? "Unpin" : "Pin"}
      >
        {pinned ? <PinOff className="h-3.5 w-3.5" /> : <Pin className="h-3.5 w-3.5" />}
      </button>
    </div>
  );
}

export function AdminBreadcrumbs({ crumbs }: { crumbs: Array<{ label: string; to?: string }> }) {
  return (
    <nav aria-label="Breadcrumb" className="flex flex-wrap items-center gap-1.5 text-xs">
      {crumbs.map((c, i) => (
        <span key={`${c.label}-${i}`} className="flex items-center gap-1.5">
          {i > 0 ? <span className="text-neutral-600">/</span> : null}
          {c.to ? (
            <Link to={c.to} className="font-medium text-neutral-400 transition hover:text-neutral-200">
              {c.label}
            </Link>
          ) : (
            <span className="font-semibold text-neutral-200">{c.label}</span>
          )}
        </span>
      ))}
    </nav>
  );
}

export function AdminQuickActions({ isLight, killActive }: { isLight: boolean; killActive?: boolean }) {
  return (
    <div className="flex items-center gap-2">
      <SafetyDiagnosticsLaunchLink variant="pill" isLight={isLight} killActive={killActive} />
      <AdminCommandPalette />
      <Link
        to="/admin/alerts"
        className={cn(
          "inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-medium transition",
          isLight
            ? "border-neutral-200 bg-white text-neutral-700 hover:border-amber-300 hover:bg-amber-50"
            : "border-neutral-800 bg-neutral-900/60 text-neutral-300 hover:border-amber-500/40 hover:bg-amber-500/10",
        )}
      >
        <Star className="h-3.5 w-3.5 text-amber-400" />
        Alerts
      </Link>
    </div>
  );
}
