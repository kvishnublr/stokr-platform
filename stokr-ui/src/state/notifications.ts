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
  push: (e: Omit<FeedEvent, "id" | "ts">) => void;
  markRead: () => void;
  clear: () => void;
};

export const useNotificationStore = create<NotificationState>((set, get) => ({
  items: [],
  unread: 0,
  push: (e) => {
    const ev: FeedEvent = {
      ...e,
      id: randomUuid(),
      ts: Date.now(),
    };
    set({
      items: [ev, ...get().items].slice(0, 50),
      unread: get().unread + 1,
    });
  },
  markRead: () => set({ unread: 0 }),
  clear: () => set({ items: [], unread: 0 }),
}));
