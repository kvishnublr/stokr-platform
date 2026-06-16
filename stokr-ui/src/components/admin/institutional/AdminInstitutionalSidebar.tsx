import { useMemo, useState } from "react";
import { NavLink, useLocation } from "react-router-dom";
import { AnimatePresence, motion } from "framer-motion";
import { ChevronDown, ChevronLeft, ChevronRight, Pin, Shield } from "lucide-react";
import { SafetyDiagnosticsLaunchLink } from "./SafetyDiagnosticsLaunchLink";
import { ADMIN_NAV_GROUPS } from "../../../admin/navigation";
import { useAdminWorkspaceStore } from "../../../admin/adminWorkspaceStore";
import { cn } from "../../../lib/utils";

export function AdminInstitutionalSidebar({ isLight, killActive }: { isLight: boolean; killActive?: boolean }) {
  const location = useLocation();
  const collapsed = useAdminWorkspaceStore((s) => s.sidebarCollapsed);
  const toggleSidebar = useAdminWorkspaceStore((s) => s.toggleSidebar);
  const pinnedRoutes = useAdminWorkspaceStore((s) => s.pinnedRoutes);
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(ADMIN_NAV_GROUPS.map((g) => [g.id, g.id === "command" || g.id === "strategies-signals"])),
  );

  const pinnedItems = useMemo(
    () =>
      pinnedRoutes
        .map((route) => ADMIN_NAV_GROUPS.flatMap((g) => g.items).find((i) => i.to === route))
        .filter(Boolean),
    [pinnedRoutes],
  );

  function toggleGroup(id: string) {
    setOpenGroups((prev) => ({ ...prev, [id]: !prev[id] }));
  }

  return (
    <div
      className={cn(
        "flex flex-col border-r transition-[width] duration-300",
        collapsed ? "w-[52px]" : "w-full",
        isLight ? "border-neutral-200 bg-white/95" : "border-neutral-900/80 bg-neutral-950/90",
      )}
    >
      <div
        className={cn(
          "flex items-center justify-between border-b px-2 py-2",
          isLight ? "border-neutral-200" : "border-neutral-900",
        )}
      >
        {!collapsed ? (
          <div className="px-1">
            <p className={cn("text-[10px] font-bold uppercase tracking-[0.16em]", isLight ? "text-blue-700" : "text-blue-400")}>
              Admin Ops
            </p>
            <p className={cn("text-[11px]", isLight ? "text-neutral-500" : "text-neutral-500")}>Institutional console</p>
          </div>
        ) : null}
        <button
          type="button"
          onClick={toggleSidebar}
          aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          className={cn(
            "rounded-lg p-1.5 transition",
            isLight ? "text-neutral-600 hover:bg-neutral-100" : "text-neutral-400 hover:bg-neutral-900",
          )}
        >
          {collapsed ? <ChevronRight className="h-4 w-4" /> : <ChevronLeft className="h-4 w-4" />}
        </button>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden py-2 [scrollbar-width:thin]">
        {collapsed ? (
          <NavLink
            to="/admin/safety-diagnostics"
            title="Safety & Diagnostics"
            className={({ isActive }) =>
              cn(
                "mx-1 mb-2 flex justify-center rounded-xl border p-2 transition hover:scale-105",
                killActive
                  ? "border-rose-500/50 bg-gradient-to-br from-rose-500/20 to-amber-500/10 text-rose-400 shadow-[0_0_16px_rgba(244,63,94,0.35)]"
                  : isLight
                    ? "border-cyan-300 bg-gradient-to-br from-cyan-100 to-violet-100 text-indigo-700 shadow-[0_4px_16px_rgba(59,130,246,0.2)]"
                    : "border-cyan-500/40 bg-gradient-to-br from-cyan-500/15 to-violet-500/15 text-cyan-300 shadow-[0_0_20px_rgba(56,189,248,0.25)]",
                isActive && "ring-2 ring-cyan-400/50",
              )
            }
          >
            <Shield className="h-4 w-4" />
          </NavLink>
        ) : (
          <SafetyDiagnosticsLaunchLink variant="sidebar" isLight={isLight} killActive={killActive} />
        )}

        {!collapsed && pinnedItems.length > 0 ? (
          <div className="mb-3 px-2">
            <p className={cn("px-2 py-1 text-[10px] font-bold uppercase tracking-widest", isLight ? "text-neutral-400" : "text-neutral-600")}>
              Pinned
            </p>
            <div className="space-y-0.5">
              {pinnedItems.map((item) => {
                if (!item) return null;
                const Icon = item.icon;
                return (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.end}
                    className={({ isActive }) =>
                      cn(
                        "flex items-center gap-2 rounded-lg px-2 py-2 text-xs font-medium transition",
                        isActive
                          ? isLight
                            ? "bg-blue-50 text-blue-800 ring-1 ring-blue-200"
                            : "bg-blue-500/15 text-blue-200 ring-1 ring-blue-500/30"
                          : isLight
                            ? "text-neutral-700 hover:bg-neutral-100"
                            : "text-neutral-400 hover:bg-neutral-900 hover:text-neutral-200",
                      )
                    }
                  >
                    <Pin className="h-3 w-3 shrink-0 text-amber-500" />
                    <Icon className="h-3.5 w-3.5 shrink-0 opacity-80" />
                    <span className="truncate">{item.label}</span>
                  </NavLink>
                );
              })}
            </div>
          </div>
        ) : null}

        {ADMIN_NAV_GROUPS.map((group) => {
          const isOpen = collapsed ? false : openGroups[group.id] !== false;
          const hasActive = group.items.some((item) => {
            const base = item.to.replace(/\/+$/, "");
            const path = location.pathname.replace(/\/+$/, "");
            return item.end ? path === base : path === base || path.startsWith(`${base}/`);
          });

          if (collapsed) {
            const activeItem = group.items.find((item) => {
              const base = item.to.replace(/\/+$/, "");
              const path = location.pathname.replace(/\/+$/, "");
              return item.end ? path === base : path === base || path.startsWith(`${base}/`);
            });
            const Icon = activeItem?.icon ?? group.items[0]?.icon;
            if (!Icon) return null;
            return (
              <NavLink
                key={group.id}
                to={activeItem?.to ?? group.items[0].to}
                title={group.title}
                className={({ isActive }) =>
                  cn(
                    "mx-1 my-0.5 flex justify-center rounded-lg p-2 transition",
                    isActive || hasActive
                      ? "bg-blue-500/15 text-blue-300"
                      : "text-neutral-500 hover:bg-neutral-900 hover:text-neutral-300",
                  )
                }
              >
                <Icon className="h-4 w-4" />
              </NavLink>
            );
          }

          return (
            <div key={group.id} className="mb-1 px-2">
              <button
                type="button"
                onClick={() => toggleGroup(group.id)}
                className={cn(
                  "flex w-full items-center justify-between rounded-lg px-2 py-1.5 text-left transition",
                  hasActive
                    ? isLight
                      ? "bg-neutral-100 text-neutral-900"
                      : "bg-neutral-900/80 text-neutral-100"
                    : isLight
                      ? "text-neutral-600 hover:bg-neutral-50"
                      : "text-neutral-500 hover:bg-neutral-900/60 hover:text-neutral-300",
                )}
              >
                <div className="min-w-0">
                  <div className="truncate text-[11px] font-semibold">{group.title}</div>
                  <div className={cn("truncate text-[10px]", isLight ? "text-neutral-400" : "text-neutral-600")}>
                    {group.subtitle}
                  </div>
                </div>
                <ChevronDown className={cn("h-3.5 w-3.5 shrink-0 transition-transform duration-300", isOpen ? "rotate-0" : "-rotate-90")} />
              </button>
              <AnimatePresence initial={false}>
                {isOpen ? (
                  <motion.div
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: "auto", opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.22, ease: [0.22, 1, 0.36, 1] }}
                    className="overflow-hidden"
                  >
                    <div className="mt-0.5 space-y-0.5 border-l border-neutral-800/80 pl-2 ml-2">
                      {group.items.map((item, idx) => {
                        const Icon = item.icon;
                        return (
                          <motion.div
                            key={item.to}
                            initial={{ opacity: 0, x: -6 }}
                            animate={{ opacity: 1, x: 0 }}
                            transition={{ delay: idx * 0.03, duration: 0.2 }}
                          >
                            <NavLink
                              to={item.to}
                              end={item.end}
                              className={({ isActive }) =>
                                cn(
                                  "flex items-center gap-2 rounded-lg px-2 py-1.5 text-[11px] font-medium transition",
                                  isActive
                                    ? isLight
                                      ? "bg-blue-50 text-blue-800"
                                      : "bg-blue-500/12 text-blue-200"
                                    : isLight
                                      ? "text-neutral-600 hover:bg-neutral-50 hover:text-neutral-900"
                                      : "text-neutral-500 hover:bg-neutral-900/50 hover:text-neutral-200",
                                )
                              }
                            >
                              <Icon className="h-3.5 w-3.5 shrink-0 opacity-75" />
                              <span className="truncate">{item.label}</span>
                              {item.tier === "critical" ? (
                                <span className="ml-auto h-1.5 w-1.5 rounded-full bg-rose-500" title="Critical" />
                              ) : null}
                            </NavLink>
                          </motion.div>
                        );
                      })}
                    </div>
                  </motion.div>
                ) : null}
              </AnimatePresence>
            </div>
          );
        })}
      </div>
    </div>
  );
}
