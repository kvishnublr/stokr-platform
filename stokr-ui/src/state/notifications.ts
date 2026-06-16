import { create } from "zustand";
import { randomUuid } from "../lib/utils";

export type FeedEvent = {
  id: string;
  ts: number;
  severity: "info" | "success" | "warning" | "error";
  title: string;
  detail?: string;
  topic?: string;
};

type NotificationState = {
  items: FeedEvent[];
  unread: number;
  seenThroughTs: number;
  push: (e: Omit<FeedEvent, "id" | "ts">) => void;
  hydrate: (incoming: FeedEvent[]) => void;
  markRead: () => void;
  clear: () => void;
};

function recalcUnread(items: FeedEvent[], seenThroughTs: number) {
  return items.filter((i) => i.ts > seenThroughTs).length;
}

function mergeItems(existing: FeedEvent[], incoming: FeedEvent[]) {
  const merged = new Map<string, FeedEvent>();
  for (const item of existing) merged.set(item.id, item);
  for (const item of incoming) merged.set(item.id, item);
  return [...merged.values()].sort((a, b) => b.ts - a.ts).slice(0, 80);
}

export const useNotificationStore = create<NotificationState>((set, get) => ({
  items: [],
  unread: 0,
  seenThroughTs: Date.now(),
  push: (e) => {
    const ev: FeedEvent = {
      ...e,
      id: randomUuid(),
      ts: Date.now(),
    };
    const items = mergeItems(get().items, [ev]);
    const seenThroughTs = get().seenThroughTs;
    set({
      items,
      unread: recalcUnread(items, seenThroughTs),
    });
  },
  hydrate: (incoming) => {
    const items = mergeItems(get().items, incoming);
    const seenThroughTs = get().seenThroughTs;
    set({
      items,
      unread: recalcUnread(items, seenThroughTs),
    });
  },
  markRead: () => {
    const now = Date.now();
    set({ seenThroughTs: now, unread: 0 });
  },
  clear: () => set({ items: [], unread: 0, seenThroughTs: Date.now() }),
}));
