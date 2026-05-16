import type { Dispatch, SetStateAction } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Filter, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import type { FeedEvent } from "../../state/notifications";
import { ActivityTimeline } from "./ActivityTimeline";

type FilterTopic = "all" | FeedEvent["topic"];

export function NotificationDrawer({
  open,
  onOpenChange,
  items,
  onClear,
}: {
  open: boolean;
  onOpenChange: Dispatch<SetStateAction<boolean>>;
  items: FeedEvent[];
  onClear: () => void;
}) {
  const [topic, setTopic] = useState<FilterTopic>("all");
  const filtered = useMemo(() => {
    if (topic === "all") return items;
    return items.filter((i) => (i.topic ?? "general") === topic);
  }, [items, topic]);

  return (
    <AnimatePresence>
      {open ? (
        <>
          <motion.button
            type="button"
            aria-label="Close alerts"
            className="fixed inset-0 z-40 bg-black/60 backdrop-blur-sm lg:bg-black/45"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => onOpenChange(false)}
          />
          <motion.aside
            role="dialog"
            aria-labelledby="notification-drawer-title"
            initial={{ x: "100%" }}
            animate={{ x: 0 }}
            exit={{ x: "100%" }}
            transition={{ type: "spring", damping: 28, stiffness: 320 }}
            className="fixed right-0 top-0 z-50 flex h-full w-full max-w-md flex-col border-l border-neutral-800 bg-neutral-950/97 shadow-[-20px_0_60px_-20px_rgba(0,0,0,0.9)] backdrop-blur-xl"
          >
            <div className="flex items-center justify-between border-b border-neutral-800 px-5 py-4">
              <div>
                <h2 id="notification-drawer-title" className="text-sm font-semibold text-white">
                  Alert center
                </h2>
                <p className="mt-1 text-[11px] text-neutral-500">WebSocket + execution feed  ·  persists for session</p>
              </div>
              <button
                type="button"
                title="Clear"
                onClick={onClear}
                className="rounded-lg border border-neutral-800 p-2 text-neutral-400 hover:bg-neutral-900 hover:text-neutral-100"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </div>

            <div className="flex gap-2 border-b border-neutral-900 px-4 py-2">
              {(
                ["all", "orders", "pnl", "strategy"] as FilterTopic[]
              ).map((f) => (
                <button
                  key={f}
                  type="button"
                  onClick={() => setTopic(f)}
                  className={`inline-flex items-center gap-1 rounded-md px-2 py-1 text-[10px] font-bold uppercase tracking-wider ${
                    topic === f
                      ? "bg-neutral-800 text-white"
                      : "text-neutral-500 hover:bg-neutral-900 hover:text-neutral-300"
                  }`}
                >
                  {f === "all" ? (
                    <>
                      <Filter className="h-3 w-3" /> All
                    </>
                  ) : (
                    f
                  )}
                </button>
              ))}
            </div>

            <div className="flex-1 overflow-y-auto px-4 py-3">
              <ActivityTimeline items={filtered} maxVisible={100} />
            </div>

            <div className="border-t border-neutral-900 px-4 py-2 text-[10px] text-neutral-600">
              Telegram/WhatsApp adapters surface here when delivery hooks are subscribed.
            </div>
          </motion.aside>
        </>
      ) : null}
    </AnimatePresence>
  );
}
