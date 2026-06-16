import { cn } from "../../lib/utils";
import { useUiThemeStore } from "../../state/uiTheme";

function useSkeletonTone() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  return {
    line: isLight
      ? "animate-pulse bg-neutral-200/80"
      : "animate-pulse bg-gradient-to-r from-neutral-900 via-neutral-800 to-neutral-900",
    card: isLight
      ? "border-neutral-200/90 bg-white/80"
      : "border-neutral-900 bg-neutral-950/70",
  };
}

export function SkeletonLine({ className }: { className?: string }) {
  const tone = useSkeletonTone();
  return <div aria-hidden className={cn("rounded-md", tone.line, className)} />;
}

export function SkeletonCard({ dense }: { dense?: boolean }) {
  const tone = useSkeletonTone();
  return (
    <div
      aria-hidden
      className={cn("animate-pulse rounded-2xl border", tone.card, dense ? "h-28" : "h-44")}
    />
  );
}

export function PageSkeleton({ cards = 4 }: { cards?: number }) {
  return (
    <div className="space-y-8">
      <SkeletonLine className="h-10 w-1/3 max-w-xs" />
      <SkeletonLine className="h-4 w-2/3 max-w-xl" />
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: cards }).map((_, i) => (
          <SkeletonCard key={i} />
        ))}
      </div>
      <SkeletonLine className="h-72 w-full rounded-2xl" />
    </div>
  );
}
