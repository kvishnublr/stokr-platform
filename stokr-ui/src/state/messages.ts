import { create } from "zustand";
import { randomUuid } from "../lib/utils";

export type OperationalMessage = {
  id: string;
  ts: number;
  severity: "info" | "success" | "warning" | "error";
  title: string;
  detail?: string;
  source?: string;
  actionPath?: string;
};

type MessagesState = {
  items: OperationalMessage[];
  unread: number;
  seenThroughTs: number;
  hydrate: (items: OperationalMessage[]) => void;
  markRead: () => void;
  clear: () => void;
};

function recalcUnread(items: OperationalMessage[], seenThroughTs: number) {
  return items.filter((i) => i.ts > seenThroughTs).length;
}

export const useMessagesStore = create<MessagesState>((set, get) => ({
  items: [],
  unread: 0,
  seenThroughTs: Date.now(),
  hydrate: (incoming) => {
    const merged = new Map<string, OperationalMessage>();
    for (const item of incoming) {
      merged.set(item.id, item);
    }
    const items = [...merged.values()].sort((a, b) => b.ts - a.ts).slice(0, 80);
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

export function buildOperationalMessage(
  partial: Omit<OperationalMessage, "id" | "ts"> & { id?: string; ts?: number },
): OperationalMessage {
  return {
    id: partial.id ?? randomUuid(),
    ts: partial.ts ?? Date.now(),
    severity: partial.severity,
    title: partial.title,
    detail: partial.detail,
    source: partial.source,
    actionPath: partial.actionPath,
  };
}
