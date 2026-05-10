import type { ComponentType, ReactNode } from "react";
import { GlassPanel } from "./GlassPanel";

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon?: ComponentType<{ className?: string; "aria-hidden"?: boolean }>;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <GlassPanel className="flex flex-col items-center justify-center px-8 py-16 text-center">
      {Icon ? <Icon className="mb-4 h-10 w-10 text-neutral-600" aria-hidden /> : null}
      <div className="text-base font-semibold text-neutral-100">{title}</div>
      {description ? <p className="mt-2 max-w-md text-sm text-neutral-500">{description}</p> : null}
      {action ? <div className="mt-6">{action}</div> : null}
    </GlassPanel>
  );
}
