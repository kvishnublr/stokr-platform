import type { ReactNode } from "react";
import { motion } from "framer-motion";
import { NavLink, useLocation } from "react-router-dom";
import type { LucideIcon } from "lucide-react";
import { Moon, Settings, Sun } from "lucide-react";
import { Link } from "react-router-dom";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";

export type SidebarLink = {
  to: string;
  end?: boolean;
  label: string;
  icon: LucideIcon;
  matchPrefix?: string;
};

function DiamondMark({ className }: { className?: string }) {
  return (
    <motion.div
      whileHover={{ scale: 1.04, rotate: 2 }}
      transition={{ type: "spring", stiffness: 420, damping: 24 }}
      className={cn(
        "relative flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-gradient-to-br from-blue-500 via-blue-600 to-indigo-700 shadow-[0_12px_30px_-8px_rgba(59,130,246,0.55)]",
        className,
      )}
      aria-hidden
    >
      <motion.div
        className="absolute inset-0 bg-gradient-to-tr from-white/25 to-transparent"
        animate={{ x: ["-120%", "120%"] }}
        transition={{ duration: 3.5, repeat: Infinity, ease: "easeInOut", repeatDelay: 2 }}
      />
      <svg viewBox="0 0 24 24" className="relative h-[22px] w-[22px] fill-white opacity-95">
        <path d="M12 2 22 12 12 22 2 12Z" />
      </svg>
    </motion.div>
  );
}

export function SidebarBrand({
  title = "Stokr Platform",
  subtitle = "Trader workspace",
}: {
  title?: string;
  subtitle?: string;
}) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  return (
    <div className="mb-6 px-2">
      <div className="flex items-center gap-3">
        <DiamondMark />
        <div>
          <div
            className={cn(
              "text-[15px] font-semibold tracking-tight",
              isLight ? "text-neutral-900" : "text-white",
            )}
          >
            {title}
          </div>
          <div
            className={cn(
              "mt-0.5 text-[10px] font-semibold uppercase tracking-[0.2em]",
              isLight ? "text-neutral-400" : "text-neutral-500",
            )}
          >
            {subtitle}
          </div>
        </div>
      </div>
    </div>
  );
}

export function SidebarLinks({ links }: { links: SidebarLink[] }) {
  const location = useLocation();
  const isLight = useUiThemeStore((s) => s.mode === "light");

  function linkClass(active: boolean) {
    return cn(
      "group relative flex items-center gap-2.5 rounded-xl px-3 py-2.5 text-[13px] font-medium tracking-tight transition duration-200",
      "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500",
      isLight &&
        cn(
          active
            ? "bg-gradient-to-r from-blue-50 to-white text-blue-800 shadow-[inset_3px_0_0_0_rgb(37,99,235),inset_0_0_0_1px_rgba(59,130,246,0.12)] ring-1 ring-blue-100/90"
            : "text-neutral-600 hover:bg-neutral-50/90 hover:text-neutral-900",
        ),
      !isLight &&
        cn(
          active
            ? "bg-gradient-to-r from-blue-500/[0.12] via-neutral-900/90 to-neutral-900 text-white shadow-[inset_0_0_0_1px_rgba(147,197,253,0.22),inset_0_-1px_0_0_rgba(0,0,0,0.4)] ring-1 ring-blue-500/20"
            : "text-neutral-400 hover:bg-neutral-900/70 hover:text-white hover:shadow-[inset_0_0_0_1px_rgba(255,255,255,0.04)]",
        ),
    );
  }

  return (
    <nav className="flex flex-col gap-0.5" aria-label="Primary">
      {links.map((item) => {
        const Icon = item.icon;
        const active =
          location.pathname === item.to ||
          Boolean(item.matchPrefix && location.pathname.startsWith(item.matchPrefix)) ||
          (!item.end && location.pathname.startsWith(`${item.to}/`));
        return (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            className={({ isActive }) =>
              linkClass(isActive || Boolean(item.matchPrefix && location.pathname.startsWith(item.matchPrefix)))
            }
          >
            {active ? (
              <motion.span
                layoutId="trader-sidebar-active"
                className={cn(
                  "absolute inset-0 rounded-xl",
                  isLight ? "bg-gradient-to-r from-blue-50 to-white ring-1 ring-blue-100/90" : "bg-gradient-to-r from-blue-500/[0.12] via-neutral-900/90 to-neutral-900 ring-1 ring-blue-500/20",
                )}
                transition={{ type: "spring", stiffness: 420, damping: 34 }}
              />
            ) : null}
            <Icon
              className={cn(
                "relative z-[1] h-4 w-4 shrink-0 opacity-80 transition group-hover:opacity-100",
                isLight ? "text-neutral-400 group-hover:text-neutral-700" : "",
              )}
              aria-hidden
            />
            <span className="relative z-[1]">{item.label}</span>
          </NavLink>
        );
      })}
    </nav>
  );
}

export function SidebarSection({
  title,
  children,
  first,
}: {
  title: string;
  children: ReactNode;
  /** First group under brand - softer top spacing */
  first?: boolean;
}) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  return (
    <div
      className={cn(!first && "mt-7 border-t pt-5", !first && (isLight ? "border-neutral-200" : "border-white/[0.06]"))}
    >
      <div className="mb-3 flex items-center gap-2 px-3">
        <span
          className={cn(
            "h-px max-w-[24px] flex-1 bg-gradient-to-r",
            isLight ? "from-blue-400/60 to-transparent" : "from-blue-500/50 to-transparent",
          )}
          aria-hidden
        />
        <div
          className={cn(
            "text-[10px] font-bold uppercase tracking-[0.22em]",
            isLight ? "text-neutral-400" : "text-neutral-500",
          )}
        >
          {title}
        </div>
      </div>
      <div className="flex flex-col gap-0.5">{children}</div>
    </div>
  );
}

export function SidebarAppearanceRow() {
  const mode = useUiThemeStore((s) => s.mode);
  const setMode = useUiThemeStore((s) => s.setMode);
  const isLight = mode === "light";

  return (
    <div className="mt-4 flex items-center gap-3 border-t border-neutral-200/80 px-2 pt-4 dark:border-white/[0.06]">
      <Link
        to="/debug"
        className={cn(
          "flex h-10 w-10 items-center justify-center rounded-xl border transition",
          isLight
            ? "border-neutral-200 bg-neutral-50 text-neutral-600 hover:bg-neutral-100"
            : "border-white/[0.08] bg-neutral-900/60 text-neutral-300 hover:bg-neutral-800",
        )}
        aria-label="Settings"
      >
        <Settings className="h-5 w-5" />
      </Link>
      <div
        className={cn(
          "flex flex-1 rounded-xl border p-0.5",
          isLight ? "border-neutral-200 bg-neutral-50" : "border-white/[0.08] bg-neutral-900/50",
        )}
        role="group"
        aria-label="Appearance"
      >
        <button
          type="button"
          onClick={() => setMode("light")}
          className={cn(
            "flex flex-1 items-center justify-center gap-1.5 rounded-lg py-2 text-[11px] font-bold uppercase tracking-wide transition",
            isLight ? "bg-white text-blue-700 shadow-sm" : "text-neutral-500 hover:text-neutral-300",
          )}
        >
          <Sun className="h-3.5 w-3.5" />
          Light
        </button>
        <button
          type="button"
          onClick={() => setMode("dark")}
          className={cn(
            "flex flex-1 items-center justify-center gap-1.5 rounded-lg py-2 text-[11px] font-bold uppercase tracking-wide transition",
            !isLight ? "bg-white text-neutral-950 shadow" : "text-neutral-500 hover:text-neutral-300",
          )}
        >
          <Moon className="h-3.5 w-3.5" />
          Dark
        </button>
      </div>
    </div>
  );
}
