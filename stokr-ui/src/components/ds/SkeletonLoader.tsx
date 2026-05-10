import { cn } from "../../lib/utils";

export function SkeletonLine({ className }: { className?: string }) {
  return (
    <div
      aria-hidden
      className={cn("animate-pulse rounded-md bg-gradient-to-r from-neutral-900 via-neutral-800 to-neutral-900", className)}
    />
  );
}

export function SkeletonCard({ dense }: { dense?: boolean }) {
  return (
    <div
      aria-hidden
      className={cn(
        "animate-pulse rounded-2xl border border-neutral-900 bg-neutral-950/70",
        dense ? "h-28" : "h-44",
      )}
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
