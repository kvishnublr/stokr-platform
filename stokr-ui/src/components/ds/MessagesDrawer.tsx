import type { Dispatch, SetStateAction } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { useNavigate } from "react-router-dom";
import { Trash2 } from "lucide-react";
import type { OperationalMessage } from "../../state/messages";
import { cn } from "../../lib/utils";

export function MessagesDrawer({
  open,
  onOpenChange,
  items,
  onClear,
}: {
  open: boolean;
  onOpenChange: Dispatch<SetStateAction<boolean>>;
  items: OperationalMessage[];
  onClear: () => void;
}) {
  const navigate = useNavigate();

  return (
    <AnimatePresence>
      {open ? (
        <>
          <motion.button
            type="button"
            aria-label="Close messages"
            className="fixed inset-0 z-40 bg-black/60 backdrop-blur-sm lg:bg-black/45"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => onOpenChange(false)}
          />
          <motion.aside
            role="dialog"
            aria-labelledby="messages-drawer-title"
            initial={{ x: "100%" }}
            animate={{ x: 0 }}
            exit={{ x: "100%" }}
            transition={{ type: "spring", damping: 28, stiffness: 320 }}
            className="fixed right-0 top-0 z-50 flex h-full w-full max-w-md flex-col border-l border-neutral-800 bg-neutral-950/97 shadow-[-20px_0_60px_-20px_rgba(0,0,0,0.9)] backdrop-blur-xl"
          >
            <div className="flex items-center justify-between border-b border-neutral-800 px-5 py-4">
              <div>
                <h2 id="messages-drawer-title" className="text-sm font-semibold text-white">
                  Messages
                </h2>
                <p className="mt-1 text-[11px] text-neutral-500">
                  Operational alerts from readiness checks and platform incidents
                </p>
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

            <div className="flex-1 overflow-y-auto px-4 py-3">
              {items.length === 0 ? (
                <p className="text-xs text-neutral-500">
                  No operational messages right now. Readiness warnings and admin incidents will appear here.
                </p>
              ) : (
                <div className="space-y-2">
                  {items.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => {
                        if (item.actionPath) {
                          navigate(item.actionPath);
                          onOpenChange(false);
                        }
                      }}
                      className={cn(
                        "w-full rounded-xl border border-neutral-800/90 bg-neutral-900/40 px-3 py-3 text-left transition hover:border-neutral-700 hover:bg-neutral-900/70",
                        item.actionPath && "cursor-pointer",
                      )}
                    >
                      <div className="flex items-start gap-2">
                        <span
                          className={cn(
                            "mt-1 h-2 w-2 shrink-0 rounded-full",
                            item.severity === "error" && "bg-rose-400",
                            item.severity === "warning" && "bg-amber-400",
                            item.severity === "success" && "bg-emerald-400",
                            item.severity === "info" && "bg-blue-400",
                          )}
                        />
                        <div className="min-w-0 flex-1">
                          <div className="text-[13px] font-medium text-neutral-100">{item.title}</div>
                          {item.detail ? (
                            <div className="mt-1 text-[11px] leading-relaxed text-neutral-500">{item.detail}</div>
                          ) : null}
                          <div className="mt-2 flex flex-wrap items-center gap-2 text-[10px] text-neutral-600">
                            {item.source ? <span>{item.source}</span> : null}
                            <span>
                              {new Date(item.ts).toLocaleString("en-IN", {
                                hour: "2-digit",
                                minute: "2-digit",
                                day: "2-digit",
                                month: "short",
                                timeZone: "Asia/Kolkata",
                              })}
                            </span>
                            {item.actionPath ? <span className="text-blue-400">Open →</span> : null}
                          </div>
                        </div>
                      </div>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </motion.aside>
        </>
      ) : null}
    </AnimatePresence>
  );
}
