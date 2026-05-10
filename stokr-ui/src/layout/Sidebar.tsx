import type { ReactNode } from "react";
import { NavLink, useLocation } from "react-router-dom";
import type { LucideIcon } from "lucide-react";
import { cn } from "../lib/utils";

export type SidebarLink = {
  to: string;
  end?: boolean;
  label: string;
  icon: LucideIcon;
  matchPrefix?: string;
};

export function SidebarBrand() {
  return (
    <div className="mb-8 px-2">
      <div className="text-[17px] font-semibold tracking-tight text-white">Stokr</div>
      <div className="mt-1 text-[11px] font-medium uppercase tracking-[0.2em] text-neutral-600">Trading OS</div>
    </div>
  );
}

export function SidebarLinks({ links }: { links: SidebarLink[] }) {
  const location = useLocation();

  function linkClass(active: boolean) {
    return cn(
      "flex items-center gap-2.5 rounded-xl px-3 py-2.5 text-[13px] font-medium tracking-tight transition",
      "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500",
      active
        ? "bg-neutral-900 text-white shadow-[inset_0_0_0_1px_rgba(255,255,255,0.06)] ring-1 ring-white/10"
        : "text-neutral-400 hover:bg-neutral-900/80 hover:text-white",
    );
  }

  return (
    <nav className="flex flex-col gap-0.5" aria-label="Primary">
      {links.map((item) => {
        const Icon = item.icon;
        return (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            className={({ isActive }) =>
              linkClass(isActive || Boolean(item.matchPrefix && location.pathname.startsWith(item.matchPrefix)))
            }
          >
            <Icon className="h-4 w-4 shrink-0 opacity-80" aria-hidden />
            {item.label}
          </NavLink>
        );
      })}
    </nav>
  );
}

export function SidebarSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="mt-8 border-t border-neutral-900 pt-5">
      <div className="mb-2 px-3 text-[10px] font-bold uppercase tracking-[0.2em] text-neutral-600">{title}</div>
      <div className="flex flex-col gap-0.5">{children}</div>
    </div>
  );
}
